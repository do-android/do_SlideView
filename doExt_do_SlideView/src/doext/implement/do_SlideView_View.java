package doext.implement;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.json.JSONException;
import org.json.JSONObject;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Message;
import android.support.v4.view.MotionEventCompat;
import android.support.v4.view.PagerAdapter;
import android.support.v4.view.ViewPager;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Interpolator;
import core.DoServiceContainer;
import core.helper.DoJsonHelper;
import core.helper.DoScriptEngineHelper;
import core.helper.DoTextHelper;
import core.helper.DoUIModuleHelper;
import core.interfaces.DoIListData;
import core.interfaces.DoIScriptEngine;
import core.interfaces.DoIUIModuleView;
import core.object.DoInvokeResult;
import core.object.DoMultitonModule;
import core.object.DoSourceFile;
import core.object.DoUIContainer;
import core.object.DoUIModule;
import doext.define.do_SlideView_IMethod;
import doext.define.do_SlideView_MAbstract;
import doext.implement.custom.DoSlideViewDurationScroller;

/**
 * 自定义扩展UIView组件实现类，此类必须继承相应VIEW类，并实现DoIUIModuleView,do_SlideView_IMethod接口；
 * #如何调用组件自定义事件？可以通过如下方法触发事件：
 * this.model.getEventCenter().fireEvent(_messageName, jsonResult);
 * 参数解释：@_messageName字符串事件名称，@jsonResult传递事件参数对象； 获取DoInvokeResult对象方式new
 * DoInvokeResult(this.getUniqueKey());
 */
@SuppressLint("ClickableViewAccessibility")
public class do_SlideView_View extends ViewPager implements DoIUIModuleView, do_SlideView_IMethod {

	/**
	 * 每个UIview都会引用一个具体的model实例；
	 */
	private do_SlideView_MAbstract model;
	private MyPagerAdapter mPagerAdapter;
	private boolean isLooping = false;
	private boolean allowGesture = true;
	private int currentItem;
	private int mLastMotionX, mLastMotionY;
	private static final int MAXANGLE = 60;
	public static final int MAX_VALUE = 10000000;

	public static final int LEFT = 0;
	public static final int RIGHT = 1;

	/** do nothing when sliding at the last or first item **/
	private static final int SLIDE_BORDER_MODE_NONE = 0;
	/** cycle when sliding at the last or first item **/
	private static final int SLIDE_BORDER_MODE_CYCLE = 1;
	/** deliver event to parent when sliding at the last or first item **/
	private static final int SLIDE_BORDER_MODE_TO_PARENT = 2;

	/** auto scroll time in milliseconds, default is {@link #DEFAULT_INTERVAL} **/
	private int interval = 300;
	/** auto scroll direction, default is {@link #RIGHT} **/
	private int direction = RIGHT;
	/**
	 * whether automatic cycle when auto scroll reaching the last or first item,
	 * default is true
	 **/
	private boolean isCycle = true;
	/**
	 * how to process when sliding at the last or first item, default is
	 * {@link #SLIDE_BORDER_MODE_NONE}
	 **/
	private int slideBorderMode = SLIDE_BORDER_MODE_NONE;
	/** whether animating when auto scroll at the last or first item **/
	private boolean isBorderAnimation = true;
	/** scroll factor for auto scroll animation, default is 1.0 **/
	private double autoScrollFactor = 1.0;
	/** scroll factor for swipe scroll animation, default is 1.0 **/
	private double swipeScrollFactor = 1.0;

	private Handler handler;
	private boolean isAutoScroll = false;
	private boolean isStopByTouch = false;
	private float touchX = 0f, downX = 0f;
	private DoSlideViewDurationScroller scroller = null;

	private static final int SCROLL_WHAT = 0;
	private DoIListData mData;

	public do_SlideView_View(Context context) {
		super(context);
		mPagerAdapter = new MyPagerAdapter();
		handler = new MyHandler(this);
		setViewPagerScroller();
	}

	@Override
	public boolean dispatchTouchEvent(MotionEvent ev) {
		int action = MotionEventCompat.getActionMasked(ev);
		if ((action == MotionEvent.ACTION_DOWN) && isAutoScroll) {
			isStopByTouch = true;
			stopAutoScroll();
		} else if (ev.getAction() == MotionEvent.ACTION_UP && isStopByTouch) {
			startAutoScroll();
		}

		if (slideBorderMode == SLIDE_BORDER_MODE_TO_PARENT || slideBorderMode == SLIDE_BORDER_MODE_CYCLE) {
			touchX = ev.getX();
			if (ev.getAction() == MotionEvent.ACTION_DOWN) {
				downX = touchX;
			}
			int currentItem = getCurrentItem();
			int pageCount = mPagerAdapter == null ? 0 : mPagerAdapter.getCount();
			/**
			 * current index is first one and slide to right or current index is
			 * last one and slide to left.<br/>
			 * if slide border mode is to parent, then
			 * requestDisallowInterceptTouchEvent false.<br/>
			 * else scroll to last one when current item is first one, scroll to
			 * first one when current item is last one.
			 */
			if ((currentItem == 0 && downX <= touchX) || (currentItem == pageCount - 1 && downX >= touchX)) {
				if (slideBorderMode == SLIDE_BORDER_MODE_TO_PARENT) {
					getParent().requestDisallowInterceptTouchEvent(false);
				} else {
					if (pageCount > 1) {
						setCurrentItem(pageCount - currentItem - 1, isBorderAnimation);
					}
					getParent().requestDisallowInterceptTouchEvent(true);
				}
				return super.dispatchTouchEvent(ev);
			}
		}
		getParent().requestDisallowInterceptTouchEvent(true);

		return super.dispatchTouchEvent(ev);

	}

	@Override
	public boolean onInterceptTouchEvent(MotionEvent e) {
		int y = (int) e.getRawY();
		int x = (int) e.getRawX();
		switch (e.getAction()) {
		case MotionEvent.ACTION_DOWN:
			mLastMotionY = y;
			mLastMotionX = x;
			break;
		}
		// 如果slideview订阅了touch事件，就拦截掉，不需要让子view执行
		if (this.model.getEventCenter().containsEvent("touch")) {
			return true;
		}
		if (!allowGesture) {
			return false;
		}
		return super.onInterceptTouchEvent(e);
	}

	@Override
	public boolean onTouchEvent(MotionEvent e) {
		int y = (int) e.getRawY();
		int x = (int) e.getRawX();
		switch (e.getAction()) {
		case MotionEvent.ACTION_MOVE:
			if (!allowGesture) {
				getParent().requestDisallowInterceptTouchEvent(false);
				return false;
			}
			int deltaX = x - mLastMotionX;
			int deltaY = y - mLastMotionY;
			int angle = (int) (Math.atan2(Math.abs(deltaY), Math.abs(deltaX)) * 100);
			if (angle > MAXANGLE) {
				getParent().requestDisallowInterceptTouchEvent(false);
				return false;
			}
			break;
		case MotionEvent.ACTION_UP:
		case MotionEvent.ACTION_CANCEL:
			deltaX = x - mLastMotionX;
			deltaY = y - mLastMotionY;
			angle = (int) (Math.atan2(Math.abs(deltaY), Math.abs(deltaX)) * 100);
			if ((deltaY == 0 || angle > MAXANGLE) && (Math.abs(deltaY) < 10 && Math.abs(deltaX) < 10)) {
				DoInvokeResult _invokeResult = new DoInvokeResult(this.model.getUniqueKey());
				JSONObject _obj = new JSONObject();
				try {
					_obj.put("index", currentItem);
				} catch (Exception ex) {
				}
				_invokeResult.setResultNode(_obj);
				this.model.getEventCenter().fireEvent("touch", _invokeResult);
			}
			if (!allowGesture) {
				return false;
			}
			break;
		}
		return super.onTouchEvent(e);
	}

	/**
	 * 初始化加载view准备,_doUIModule是对应当前UIView的model实例
	 */
	@Override
	public void loadView(DoUIModule _doUIModule) throws Exception {
		this.model = (do_SlideView_MAbstract) _doUIModule;
		this.setOnPageChangeListener(new MyPageChangeListener());
	}

	/**
	 * 动态修改属性值时会被调用，方法返回值为true表示赋值有效，并执行onPropertiesChanged，否则不进行赋值；
	 * 
	 * @_changedValues<key,value>属性集（key名称、value值）；
	 */
	@Override
	public boolean onPropertiesChanging(Map<String, String> _changedValues) {
		if (_changedValues.containsKey("templates")) {
			String value = _changedValues.get("templates");
			if ("".equals(value)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * 属性赋值成功后被调用，可以根据组件定义相关属性值修改UIView可视化操作；
	 * 
	 * @_changedValues<key,value>属性集（key名称、value值）；
	 */
	@Override
	public void onPropertiesChanged(Map<String, String> _changedValues) {
		DoUIModuleHelper.handleBasicViewProperChanged(this.model, _changedValues);
		if (_changedValues.containsKey("templates")) {
			initViewTemplate(_changedValues.get("templates"));
			this.setAdapter(mPagerAdapter);
		}
		if (_changedValues.containsKey("looping")) {
			isLooping = DoTextHelper.strToBool(_changedValues.get("looping"), false);
		}
		if (_changedValues.containsKey("index")) {
			currentItem = DoTextHelper.strToInt(_changedValues.get("index"), 0);
			setSelection();
		}
		if (_changedValues.containsKey("allowGesture")) {
			allowGesture = DoTextHelper.strToBool(_changedValues.get("allowGesture"), false);
		}
	}

	private void setSelection() {
		if (currentItem < 0) {
			currentItem = 0;
		}
		if (mData != null && mData.getCount() > 0) {
			int _maxCount = mData.getCount() - 1;
			if (mPagerAdapter != null && currentItem > _maxCount) {
				currentItem = _maxCount;
			}
			int _currentItem = this.currentItem;
			if (isLooping) {
				_currentItem = getRealPosition(this.currentItem);
				this.setCurrentItem(_currentItem, false);
				mPagerAdapter.refreshItems();
			} else {
				this.setCurrentItem(_currentItem, false);
			}
		}
	}

	// 当looping = true 时，计算出一个合适的position
	private int getRealPosition(int _index) {
		int _centerPos = MAX_VALUE / 2;
		int _count = mData.getCount(); // 获取数据条数
		_centerPos = (_centerPos / _count) * _count + _index;
		return _centerPos;
	}

	private void initViewTemplate(String data) {
		try {
			mPagerAdapter.initTemplates(data.split(","));
		} catch (Exception e) {
			DoServiceContainer.getLogEngine().writeError("解析cell属性错误： \t", e);
		}
	}

	/**
	 * 同步方法，JS脚本调用该组件对象方法时会被调用，可以根据_methodName调用相应的接口实现方法；
	 * 
	 * @_methodName 方法名称
	 * @_dictParas 参数（K,V）
	 * @_scriptEngine 当前Page JS上下文环境对象
	 * @_invokeResult 用于返回方法结果对象
	 */
	@Override
	public boolean invokeSyncMethod(String _methodName, JSONObject _dictParas, DoIScriptEngine _scriptEngine, DoInvokeResult _invokeResult) throws Exception {
		if ("bindItems".equals(_methodName)) {
			bindItems(_dictParas, _scriptEngine, _invokeResult);
			return true;
		}
		if ("refreshItems".equals(_methodName)) {
			mPagerAdapter.refreshItems();
			setSelection();
			return true;
		}
		if ("startLoop".equals(_methodName)) {
			startLoop(_dictParas, _scriptEngine, _invokeResult);
			return true;
		}
		if ("stopLoop".equals(_methodName)) {
			stopLoop();
			return true;
		}
		if ("getView".equals(_methodName)) {
			getView(_dictParas, _scriptEngine, _invokeResult);
			return true;
		}
		return false;
	}

	@Override
	public void bindItems(JSONObject _dictParas, DoIScriptEngine _scriptEngine, DoInvokeResult _invokeResult) throws Exception {
		String _address = DoJsonHelper.getString(_dictParas, "data", "");
		if (_address == null || _address.length() <= 0)
			throw new Exception("doSlideView 未指定 data参数！");
		DoMultitonModule _multitonModule = DoScriptEngineHelper.parseMultitonModule(_scriptEngine, _address);
		if (_multitonModule == null)
			throw new Exception("doSlideView data参数无效！");
		if (_multitonModule instanceof DoIListData) {
			mData = (DoIListData) _multitonModule;
			mPagerAdapter.bindData(mData);
			setSelection();
		}
	}

	/**
	 * 异步方法（通常都处理些耗时操作，避免UI线程阻塞），JS脚本调用该组件对象方法时会被调用， 可以根据_methodName调用相应的接口实现方法；
	 * 
	 * @_methodName 方法名称
	 * @_dictParas 参数（K,V）
	 * @_scriptEngine 当前page JS上下文环境
	 * @_callbackFuncName 回调函数名 #如何执行异步方法回调？可以通过如下方法：
	 *                    _scriptEngine.callback(_callbackFuncName,
	 *                    _invokeResult);
	 *                    参数解释：@_callbackFuncName回调函数名，@_invokeResult传递回调函数参数对象；
	 *                    获取DoInvokeResult对象方式new
	 *                    DoInvokeResult(this.getUniqueKey());
	 */
	@Override
	public boolean invokeAsyncMethod(String _methodName, JSONObject _dictParas, DoIScriptEngine _scriptEngine, String _callbackFuncName) {
		return false;
	}

	/**
	 * 释放资源处理，前端JS脚本调用closePage或执行removeui时会被调用；
	 */
	@Override
	public void onDispose() {

	}

	/**
	 * 重绘组件，构造组件时由系统框架自动调用；
	 * 或者由前端JS脚本调用组件onRedraw方法时被调用（注：通常是需要动态改变组件（X、Y、Width、Height）属性时手动调用）
	 */
	@Override
	public void onRedraw() {
		this.setLayoutParams(DoUIModuleHelper.getLayoutParams(this.model));
	}

	@SuppressLint("UseSparseArrays")
	private class MyPagerAdapter extends PagerAdapter {

		private Map<Integer, View> views = new HashMap<Integer, View>(); // 缓存所有的view
		private Map<Integer, JSONObject> viewDatas = new HashMap<Integer, JSONObject>(); // 缓存所有view 对应的数据
		private Map<Integer, String> viewTemplates = new HashMap<Integer, String>();// 缓存所有的模板路径
		private Map<String, String> itemTemplates = new HashMap<String, String>();
		private List<String> uiTemplates = new LinkedList<String>();
		private DoIListData listData;

		public void bindData(DoIListData _listData) {
			this.listData = _listData;
			notifyDataSetChanged();
		}

		public void refreshItems() {
//			for (int position : views.keySet()) {
//				DoIUIModuleView _view = (DoIUIModuleView) views.get(position);
//				_view.getModel().dispose();
//				removeView((View) _view);
//			}
//			views.clear();
			removeAllViews(); //保证viewPager 中的view 都是最新的
			notifyDataSetChanged();
		}

		private int getLength() {
			if (listData == null) {
				return 0;
			}
			return listData.getCount();
		}

		public int getPosition(int position) {
			int len = getLength();
			if (len == 0) {
				return 0;
			}
			int newPos = position % len;
			return isLooping ? newPos : position;
		}

		public String getModuleAddress(int position) {
			if (views.containsKey(position)) {
				View _mView = views.get(position);
				if (_mView instanceof DoIUIModuleView) {
					return ((DoIUIModuleView) _mView).getModel().getUniqueKey();
				}
			}
			return null;
		}

		public void initTemplates(String[] templates) throws Exception {
			uiTemplates.clear();
			for (String templatePath : templates) {
				if (templatePath != null && !templatePath.equals("")) {
					DoSourceFile _sourceFile = model.getCurrentPage().getCurrentApp().getSourceFS().getSourceByFileName(templatePath);
					if (_sourceFile != null) {
						itemTemplates.put(templatePath, _sourceFile.getTxtContent());
						uiTemplates.add(templatePath);
					} else {
						throw new RuntimeException("试图使用一个无效的UI页面:" + templatePath);
					}
				}
			}
		}

		@Override
		public void destroyItem(ViewGroup container, int position, Object object) {
		}

		@Override
		public Object instantiateItem(ViewGroup container, int position) {
			View view = null;
			int _pos = getPosition(position);
			try {
				JSONObject _childData = (JSONObject) listData.getData(_pos);
				int _index = DoTextHelper.strToInt(DoJsonHelper.getString(_childData, "template", "0"), -1);
				String _templatePath = uiTemplates.get(_index);
				if (_templatePath == null) {
					throw new RuntimeException("绑定一个无效的模版Index值");
				}
				if (views.get(_pos) == null) { // 如果为空，则加载对应的ui，对应的ui.js, setDatamodel对应的值
					view = getView(_pos, _templatePath, _childData);
				} else {
					if (_templatePath.equals(viewTemplates.get(_pos))) {
						view = views.get(_pos);
						// 判断数据是否加载的数据是否相同，不同直接setModelData
						if (!_childData.toString().equals(viewDatas.get(_pos).toString())) {
							viewDatas.put(_pos, _childData);
							((DoIUIModuleView) view).getModel().setModelData(_childData);
						}
					} else { // 如果与缓存的模板不相等，则把viewmodel dispose掉，完全重新加载对应的ui，对应的ui.js, setDatamodel对应的值
						// 先dispose对应的module
						DoIUIModuleView _view = (DoIUIModuleView) views.get(_pos);
						_view.getModel().dispose();
						removeView((View) _view);
						views.remove(_pos);
						// 创建view
						view = getView(_pos, _templatePath, _childData);
					}
				}

				if (isLooping) { // TODO 可能不需要这段代码
					DoUIModuleHelper.removeFromSuperview(view);
				}
				if (view.getParent() == null) {
					container.addView(view);
				}
			} catch (Exception e) {
				DoServiceContainer.getLogEngine().writeError("解析data数据错误： \t", e);
			}
			return view;
		}

		private View getView(int _pos, String _templatePath, JSONObject _childData) throws Exception {
			String _content = itemTemplates.get(_templatePath);
			DoUIContainer _doUIContainer = new DoUIContainer(model.getCurrentPage());
			_doUIContainer.loadFromContent(_content, null, null);
			_doUIContainer.loadDefalutScriptFile(_templatePath);
			DoIUIModuleView _doIUIModuleView = _doUIContainer.getRootView().getCurrentUIModuleView();
			View _view = (View) _doIUIModuleView;
			views.put(_pos, _view);
			viewDatas.put(_pos, _childData);
			viewTemplates.put(_pos, _templatePath);
			_doIUIModuleView.getModel().setModelData(_childData);
			return _view;
		}

		@Override
		public int getCount() {
			int len = getLength();
			if (len == 0) {
				return 0;
			}
			return isLooping ? MAX_VALUE : len;
		}

		@Override
		public boolean isViewFromObject(View arg0, Object arg1) {
			return arg0 == arg1;
		}

		/**
		 * 解决调用notifyDataSetChanged失效的问题 所有child
		 * view位置均为POSITION_NONE，表示所有的child
		 * view都不存在，ViewPager会调用destroyItem方法销毁，并且重新生成
		 */
		@Override
		public int getItemPosition(Object object) {
			return POSITION_NONE;
		}
	}

	private class MyPageChangeListener implements ViewPager.OnPageChangeListener {

		@Override
		public void onPageScrollStateChanged(int state) {
		}

		@Override
		public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {
			if (positionOffset == 0.0 && positionOffsetPixels == 0) {
				getParent().requestDisallowInterceptTouchEvent(false);
			}
		}

		@Override
		public void onPageSelected(int position) {
			try {
				int index = mPagerAdapter.getPosition(position);
				currentItem = index;
				if (!isLooping) {
					int count = getAdapter().getCount() - 2;
					if (position < 1) {
						direction = RIGHT;
					} else if (position > count) {
						direction = LEFT;
					}
				}
				model.setPropertyValue("index", index + "");
				DoInvokeResult invokeResult = new DoInvokeResult(model.getUniqueKey());
				invokeResult.setResultInteger(index);
				model.getEventCenter().fireEvent("indexChanged", invokeResult);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}

	/**
	 * 获取当前model实例
	 */
	@Override
	public DoUIModule getModel() {
		return model;
	}

	@Override
	public void startLoop(JSONObject _dictParas, DoIScriptEngine _scriptEngine, DoInvokeResult _invokeResult) throws JSONException {
		// 通过JSON解析获取到JS端传来的间隔时间interval
		this.interval = DoJsonHelper.getInt(_dictParas, "interval", interval);
		startAutoScroll();
	}

	@Override
	public void getView(JSONObject _dictParas, DoIScriptEngine _scriptEngine, DoInvokeResult _invokeResult) throws Exception {
		int _index = DoJsonHelper.getInt(_dictParas, "index", -1);
		if (_index < 0) {
			throw new Exception("index 值为空或者不正确！");
		}
		_invokeResult.setResultText(mPagerAdapter.getModuleAddress(_index));
	}

	private void stopLoop() {
		isStopByTouch = false;
		stopAutoScroll();
	}

	private static class MyHandler extends Handler {
		private final WeakReference<do_SlideView_View> autoScrollViewPager;

		public MyHandler(do_SlideView_View autoScrollViewPager) {
			this.autoScrollViewPager = new WeakReference<do_SlideView_View>(autoScrollViewPager);
		}

		@Override
		public void handleMessage(Message msg) {
			super.handleMessage(msg);

			switch (msg.what) {
			case SCROLL_WHAT:
				do_SlideView_View pager = this.autoScrollViewPager.get();
				if (pager != null) {
					pager.scroller.setScrollDurationFactor(pager.autoScrollFactor);
					pager.scrollOnce();
					pager.scroller.setScrollDurationFactor(pager.swipeScrollFactor);
					pager.sendScrollMessage(pager.interval + pager.scroller.getDuration());
				}
			default:
				break;
			}
		}
	}

	/**
	 * start auto scroll, first scroll delay time is {@link #getInterval()}
	 */
	private void startAutoScroll() {
		DoSlideViewDurationScroller.isAnim = true;
		isAutoScroll = true;
		sendScrollMessage((long) (interval + scroller.getDuration() / autoScrollFactor * swipeScrollFactor));
	}

	/**
	 * stop auto scroll
	 */
	private void stopAutoScroll() {
		DoSlideViewDurationScroller.isAnim = false;
		isAutoScroll = false;
		handler.removeMessages(SCROLL_WHAT);
	}

	private void sendScrollMessage(long delayTimeInMills) {
		/** remove messages before, keeps one message is running at most **/
		handler.removeMessages(SCROLL_WHAT);
		handler.sendEmptyMessageDelayed(SCROLL_WHAT, delayTimeInMills);
	}

	/**
	 * set ViewPager scroller to change animation duration when sliding
	 */
	private void setViewPagerScroller() {
		try {

			Field scrollerField = ViewPager.class.getDeclaredField("mScroller");
			scrollerField.setAccessible(true);
			Field interpolatorField = ViewPager.class.getDeclaredField("sInterpolator");
			interpolatorField.setAccessible(true);

			scroller = new DoSlideViewDurationScroller(getContext(), (Interpolator) interpolatorField.get(null));
			scroller.setScrollDuration(900);// 设置时间，时间越长，速度越慢
			DoSlideViewDurationScroller.isAnim = false;
			scrollerField.set(this, scroller);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	/**
	 * scroll only once
	 */
	private void scrollOnce() {
		int currentItem = getCurrentItem();
		int totalCount;
		if (mPagerAdapter == null || (totalCount = mPagerAdapter.getCount()) <= 1) {
			return;
		}

		int nextItem = (direction == LEFT) ? --currentItem : ++currentItem;
		if (nextItem < 0) {
			if (isCycle) {
				setCurrentItem(totalCount - 1, isBorderAnimation);
			}
		} else if (nextItem == totalCount) {
			if (isCycle) {

				setCurrentItem(0, isBorderAnimation);
			}
		} else {
			setCurrentItem(nextItem, true);
		}
	}
}