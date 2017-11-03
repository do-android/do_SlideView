package doext.implement.custom;

import android.content.Context;
import android.view.animation.Interpolator;
import android.widget.Scroller;

public class DoSlideViewDurationScroller extends Scroller {
	private double scrollFactor = 1;
	private double scrollFactorAnim = 1;
	public static boolean isAnim = true;

	public DoSlideViewDurationScroller(Context context) {
		super(context);
	}

	public DoSlideViewDurationScroller(Context context, Interpolator interpolator) {
		super(context, interpolator);
	}

	public void setScrollDuration(int duration) {
		this.scrollFactor = duration;
	}

	/**
	 * not exist in android 2.3
	 * 
	 * @param context
	 * @param interpolator
	 * @param flywheel
	 */

	/**
	 * Set the factor by which the duration will change
	 */
	public void setScrollDurationFactor(double scrollFactor) {
		if (isAnim) {
			this.scrollFactorAnim = scrollFactor;
		} else {
			this.scrollFactor = scrollFactor;
		}
	}

	@Override
	public void startScroll(int startX, int startY, int dx, int dy, int duration) {
		if (isAnim) {
			super.startScroll(startX, startY, dx, dy, (int) (duration * scrollFactorAnim));
		} else {
			super.startScroll(startX, startY, dx, dy, (int) (scrollFactor));
		}
	}

	@Override
	public void startScroll(int startX, int startY, int dx, int dy) {
		if (!isAnim) {
			super.startScroll(startX, startY, dx, dy, (int) scrollFactor);
		}

	}
}