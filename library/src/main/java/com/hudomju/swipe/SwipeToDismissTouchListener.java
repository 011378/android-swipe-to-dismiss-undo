/*
 * Copyright 2013 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hudomju.swipe;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Rect;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewPropertyAnimator;
import android.widget.AbsListView;
import android.widget.ListView;

import com.hudomju.swipe.adapter.ViewAdapter;

/**
 * A {@link android.view.View.OnTouchListener} that makes the list items in a collection view dismissable. It is given special treatment
 * because by default it handles touches for its list items... i.e. it's in charge of drawing the pressed state (the list selector),
 * handling list item clicks, etc.
 *
 * <p>
 * After creating the listener, the caller should also call
 * {@link android.widget.ListView#setOnScrollListener(android.widget.AbsListView.OnScrollListener)}, passing in the scroll listener returned
 * by {@link #makeScrollListener()}. If a scroll listener is already assigned, the caller should still pass scroll changes through to this
 * listener. This will ensure that this {@link SwipeToDismissTouchListener} is paused during list view scrolling.
 * </p>
 *
 * <p>
 * Example usage:
 * </p>
 *
 * <pre>
 * SwipeDismissRecyclerViewTouchListener touchListener = new SwipeDismissRecyclerViewTouchListener(
 * 		new RecyclerViewAdapter(recyclerView),
 * 		new SwipeDismissRecyclerViewTouchListener.OnDismissCallback() {
 * 			public void onDismiss(ListView listView, int[] reverseSortedPositions) {
 * 				for (int position : reverseSortedPositions) {
 * 					adapter.remove(adapter.getItem(position));
 *                }
 * 				adapter.notifyDataSetChanged();
 *            }
 *        });
 * recyclerView.setOnTouchListener(touchListener);
 * recyclerView.setOnScrollListener(touchListener.makeScrollListener());
 * </pre>
 *
 * <p>
 * This class Requires API level 12 or later due to use of {@link android.view.ViewPropertyAnimator}.
 * </p>
 */
public class SwipeToDismissTouchListener<SomeCollectionView extends ViewAdapter> implements View.OnTouchListener {

	// Cached ViewConfiguration and system-wide constant values
	private int mSlop;

	private int mMinFlingVelocity;

	private int mMaxFlingVelocity;

	private long mAnimationTime;

	// Fixed properties
	private SomeCollectionView mRecyclerView;

	private DismissCallbacks<SomeCollectionView> mCallbacks;

	private int mViewWidth = 1; // 1 and not 0 to prevent dividing by zero

	// Transient properties
	private PendingDismissData mPendingDismiss;

	private float mDownX;

	private float mDownY;

	private boolean mSwiping;

	private int mSwipingSlop;

	private VelocityTracker mVelocityTracker;

	private int mDownPosition;

	private RowContainer mRowContainer;

	private boolean mPaused;

	// Handler to dismiss pending items after a delay
	private final Handler mHandler;

	private final Runnable mDismissRunnable = new Runnable() {
		@Override
		public void run() {
			processPendingDismisses();
		}
	};

	private long mDismissDelayMillis = -1L; // negative to disable automatic dismissing

	private boolean mAlphaAnimationEnabled = true;

	public enum SwipeDirection {
		NONE, FROM_RIGHT, FROM_LEFT
	}

	public enum DismissState {
		NONE, UP_TO_DISMISS, PENDING_DISMISS
	}

	public enum CanDismissState {
		NONE, TRUE, FALSE
	}

	public class RowContainer {
		final View container;

		final View dataContainer;

		final View rightUndoContainer;

		final View leftUndoContainer;

		final View rightUpToDismissContainer;

		final View leftUpToDismissContainer;

		CanDismissState rightCanDismissState = CanDismissState.NONE;

		CanDismissState leftCanDismissState = CanDismissState.NONE;

		DismissState dismissState = DismissState.NONE;
		;

		SwipeDirection direction = SwipeDirection.NONE;

		boolean animationInProgress = false;

		public RowContainer(ViewGroup container) {
			this.container = container;
			dataContainer = container.findViewWithTag("dataContainer");
			rightUndoContainer = container.findViewWithTag("rightUndoContainer");
			leftUndoContainer = container.findViewWithTag("leftUndoContainer");
			rightUpToDismissContainer = container.findViewWithTag("rightUpToDismissContainer");
			leftUpToDismissContainer = container.findViewWithTag("leftUpToDismissContainer");
		}

		/**
		 * The view which should be swiped outside of the window. This one is used during drag & drop.
		 */
		public View getCurrentSwipingView() {
			// Use Undo-views if action should be reverted
			if (dismissState == DismissState.PENDING_DISMISS) {
				if (direction == SwipeDirection.FROM_RIGHT) {
					return rightUndoContainer;
				} else if (direction == SwipeDirection.FROM_LEFT) {
					return leftUndoContainer;
				}
			}

			return dataContainer;
		}
	}

	/**
	 * The callback interface used by {@link SwipeToDismissTouchListener} to inform its client about a successful dismissal of one or more
	 * list item positions.
	 */
	public interface DismissCallbacks<SomeCollectionView extends ViewAdapter> {
		/**
		 * Called to determine whether the given position can be dismissed.
		 */
		boolean canDismiss(int position, SwipeDirection direction);

		/**
		 * Called when an item is swiped away by the user and the undo layout is completely visible. Do NOT remove the list item yet, that
		 * should be done in onDismiss. This may also be called immediately before and item is completely dismissed.
		 *
		 * @param recyclerView The originating {@link android.support.v7.widget.RecyclerView}.
		 * @param position     The position of the dismissed item.
		 * @param direction    The direction of the item which is pending for dismissal.
		 */
		void onPendingDismiss(SomeCollectionView recyclerView, int position, SwipeDirection direction);

		/**
		 * Called when the item is completely dismissed and removed from the list, after the undo layout is hidden.
		 *
		 * @param recyclerView The originating {@link android.support.v7.widget.RecyclerView}.
		 * @param position     The position of the dismissed item.
		 */
		void onDismiss(SomeCollectionView recyclerView, int position, SwipeDirection direction);
	}

	/**
	 * Constructs a new swipe-to-dismiss touch listener for the given list view.
	 *
	 * @param recyclerView The list view whose items should be dismissable.
	 * @param callbacks    The callback to trigger when the user has indicated that she would like to dismiss one or more list items.
	 */
	public SwipeToDismissTouchListener(SomeCollectionView recyclerView, DismissCallbacks<SomeCollectionView> callbacks) {
		ViewConfiguration vc = ViewConfiguration.get(recyclerView.getContext());
		mSlop = vc.getScaledTouchSlop();
		mMinFlingVelocity = vc.getScaledMinimumFlingVelocity() * 16;
		mMaxFlingVelocity = vc.getScaledMaximumFlingVelocity();
		mAnimationTime = recyclerView.getContext().getResources().getInteger(android.R.integer.config_shortAnimTime);
		mRecyclerView = recyclerView;
		mCallbacks = callbacks;
		mHandler = new Handler();
	}

	/**
	 * Enables or disables (pauses or resumes) watching for swipe-to-dismiss gestures.
	 *
	 * @param enabled Whether or not to watch for gestures.
	 */
	public void setEnabled(boolean enabled) {
		mPaused = !enabled;
	}

	/**
	 * Set the delay after which the pending items will be dismissed when there was no user action. Set to a negative value to disable
	 * automatic dismissing items.
	 *
	 * @param dismissDelayMillis The delay between onPendingDismiss and onDismiss calls, in milliseconds.
	 */
	public void setDismissDelay(long dismissDelayMillis) {
		this.mDismissDelayMillis = dismissDelayMillis;
	}

	/**
	 * Set if there should be an alpha transition when swiping
	 *
	 * @param enableAlphaAnimation Should there be shown an alpha animation.
	 */
	public void setAlphaAnimation(boolean enableAlphaAnimation) {
		this.mAlphaAnimationEnabled = enableAlphaAnimation;
	}

	/**
	 * Set the slop if there should be any. If this method is not called the sytsems default value si used.
	 */
	public void setSlop(int slop) {
		mSlop = slop;
	}

	/**
	 * Returns an {@link android.widget.AbsListView.OnScrollListener} to be added to the {@link android.widget.ListView} using
	 * {@link android.widget.ListView#setOnScrollListener(android.widget.AbsListView.OnScrollListener)}. If a scroll listener is already
	 * assigned, the caller should still pass scroll changes through to this listener. This will ensure that this
	 * {@link SwipeToDismissTouchListener} is paused during list view scrolling.</p>
	 *
	 * @see SwipeToDismissTouchListener
	 */
	public Object makeScrollListener() {
		return mRecyclerView.makeScrollListener(new AbsListView.OnScrollListener() {
			@Override
			public void onScrollStateChanged(AbsListView absListView, int scrollState) {
				processPendingDismisses();

				boolean scrollIsEnabled = scrollState == AbsListView.OnScrollListener.SCROLL_STATE_TOUCH_SCROLL;
				setEnabled(!scrollIsEnabled);
			}

			@Override
			public void onScroll(AbsListView absListView, int i, int i1, int i2) {
			}
		});
	}

	@Override
	public boolean onTouch(View view, MotionEvent motionEvent) {
		if (mViewWidth < 2) {
			mViewWidth = mRecyclerView.getWidth();
		}

		switch (motionEvent.getActionMasked()) {
			case MotionEvent.ACTION_DOWN: {
				if (mPaused) {
					return false;
				}

				// TODO: ensure this is a finger, and set a flag

				// Find the child view that was touched (perform a hit test)
				Rect rect = new Rect();
				int childCount = mRecyclerView.getChildCount();
				int[] listViewCoords = new int[2];
				mRecyclerView.getLocationOnScreen(listViewCoords);
				int x = (int) motionEvent.getRawX() - listViewCoords[0];
				int y = (int) motionEvent.getRawY() - listViewCoords[1];
				View child;
				for (int i = 0; i < childCount; i++) {
					child = mRecyclerView.getChildAt(i);
					child.getHitRect(rect);
					if (rect.contains(x, y)) {
						assert (child instanceof ViewGroup && ((ViewGroup) child).getChildCount() == 2) : "Each child needs to extend from ViewGroup and have two children";

						boolean dataContainerHasBeenDismissed = (mPendingDismiss != null)
							&& mPendingDismiss.position == mRecyclerView.getChildPosition(child)
							&& (mPendingDismiss.rowContainer.dismissState == DismissState.PENDING_DISMISS);

						if (mRowContainer == null) {
							mRowContainer = new RowContainer((ViewGroup) child);

							if (dataContainerHasBeenDismissed) {
								mRowContainer.dismissState = DismissState.PENDING_DISMISS;
							} else {
								mRowContainer.dismissState = DismissState.NONE;
							}
						}

						break;
					}
				}

				if (mRowContainer != null) {
					mDownX = motionEvent.getRawX();
					mDownY = motionEvent.getRawY();
					mDownPosition = mRecyclerView.getChildPosition(mRowContainer.container);
					mVelocityTracker = VelocityTracker.obtain();
					mVelocityTracker.addMovement(motionEvent);
				}

				return false;
			}

			case MotionEvent.ACTION_CANCEL: {
				if (mVelocityTracker == null) {
					break;
				}

				if ((mRowContainer != null) && (mRowContainer.getCurrentSwipingView() != null) && mSwiping) {
					// cancel
					mRowContainer.dismissState = DismissState.NONE;

					setRowTransition(true, 0);
				}

				tearDownRowContainerData();
				break;
			}

			case MotionEvent.ACTION_UP: {
				if ((mVelocityTracker == null) || (mRowContainer == null) || (mRowContainer.getCurrentSwipingView() == null)) {
					//		|| (mRowContainer.dismissState == DismissState.PENDING_DISMISS)) {
					break;
				}

				float deltaX = motionEvent.getRawX() - mDownX;
				mVelocityTracker.addMovement(motionEvent);
				mVelocityTracker.computeCurrentVelocity(1000);
				float velocityX = mVelocityTracker.getXVelocity();
				float absVelocityX = Math.abs(velocityX);
				float absVelocityY = Math.abs(mVelocityTracker.getYVelocity());
				boolean dismiss = false;
				boolean dismissRight = false;

				if (Math.abs(deltaX) > mViewWidth / 2 && mSwiping) {
					dismissRight = deltaX > 0;

					if ((dismissRight && (mRowContainer.leftCanDismissState == CanDismissState.TRUE))
						|| (!dismissRight && (mRowContainer.rightCanDismissState == CanDismissState.TRUE))) {
						dismiss = true;
					}
				} else if (mMinFlingVelocity <= absVelocityX && absVelocityX <= mMaxFlingVelocity && absVelocityY < absVelocityX && mSwiping) {
					// dismiss only if flinging in the same direction as dragging
					dismissRight = mVelocityTracker.getXVelocity() > 0;

					if ((dismissRight && (mRowContainer.leftCanDismissState == CanDismissState.TRUE))
						|| (!dismissRight && (mRowContainer.rightCanDismissState == CanDismissState.TRUE))) {
						dismiss = (velocityX < 0) == (deltaX < 0);
					}
				}

				if (dismiss && mDownPosition != ListView.INVALID_POSITION) {
					// dismiss
					final RowContainer downView = mRowContainer; // mDownView gets null'd before animation ends
					final int downPosition = mDownPosition;

					int translationX = dismissRight ? mViewWidth : -mViewWidth;
					AnimatorListenerAdapter listener = new AnimatorListenerAdapter() {
						@Override
						public void onAnimationEnd(Animator animation) {
							tearDownRowContainerData();
							performDismiss(downView, downPosition);
						}
					};

					setRowTransition(true, translationX, 1, listener);
				} else {
					if (mRowContainer.dismissState == DismissState.PENDING_DISMISS) {
						return true;//undoPendingDismiss();
					}

					// cancel
					setRowTransition(true, 0);
				}

				break;
			}

			case MotionEvent.ACTION_MOVE: {
				if (mVelocityTracker == null || mPaused || (mRowContainer == null) || (mRowContainer.getCurrentSwipingView() == null)
					|| (mRowContainer.dismissState == DismissState.PENDING_DISMISS)) {
					break;
				}

				mVelocityTracker.addMovement(motionEvent);
				float deltaX = motionEvent.getRawX() - mDownX;
				float deltaY = motionEvent.getRawY() - mDownY;
				if (Math.abs(deltaX) > mSlop && Math.abs(deltaY) < Math.abs(deltaX) / 2) {
					mSwiping = true;
					mSwipingSlop = (deltaX > 0 ? mSlop : -mSlop);
					mRecyclerView.requestDisallowInterceptTouchEvent(true);

					// Cancel ListView's touch (un-highlighting the item)
					MotionEvent cancelEvent = MotionEvent.obtain(motionEvent);
					cancelEvent.setAction(MotionEvent.ACTION_CANCEL | (motionEvent.getActionIndex() << MotionEvent.ACTION_POINTER_INDEX_SHIFT));
					mRecyclerView.onTouchEvent(cancelEvent);
					cancelEvent.recycle();
				}

				if (mSwiping) {
					// Which direction is the user swiping
					if (deltaX > 0) {
						mRowContainer.direction = SwipeDirection.FROM_LEFT;
					} else {
						mRowContainer.direction = SwipeDirection.FROM_RIGHT;
					}

					// Valid click happened before
					if ((mDownPosition != ListView.INVALID_POSITION) || (mRowContainer.direction != SwipeDirection.NONE)) {

						// Set canDismiss states just in time
						if ((mRowContainer.leftCanDismissState == CanDismissState.NONE)
							&& (mRowContainer.direction == SwipeDirection.FROM_LEFT)) {
							mRowContainer.leftCanDismissState = mCallbacks.canDismiss(mDownPosition, mRowContainer.direction) ? CanDismissState.TRUE
								: CanDismissState.FALSE;
						} else if ((mRowContainer.rightCanDismissState == CanDismissState.NONE)
							&& (mRowContainer.direction == SwipeDirection.FROM_RIGHT)) {
							mRowContainer.rightCanDismissState = mCallbacks.canDismiss(mDownPosition, mRowContainer.direction) ? CanDismissState.TRUE
								: CanDismissState.FALSE;
						}
					}

					if (((mRowContainer.leftCanDismissState == CanDismissState.TRUE) && (deltaX > 0))
						|| ((mRowContainer.rightCanDismissState == CanDismissState.TRUE) && (deltaX < 0))) {
						if (deltaX > 0) {
							mRowContainer.leftUpToDismissContainer.setVisibility(View.VISIBLE);
							mRowContainer.rightUpToDismissContainer.setVisibility(View.GONE);
						} else {
							mRowContainer.rightUpToDismissContainer.setVisibility(View.VISIBLE);
							mRowContainer.leftUpToDismissContainer.setVisibility(View.GONE);
						}

						float translationX = deltaX - mSwipingSlop;
						float alpha = Math.max(0f, Math.min(1f, 1f - 2f * Math.abs(deltaX) / mViewWidth));
						setRowTransition(false, translationX, alpha);
					}

					return true;
				}

				break;
			}
		}
		return false;
	}

	private void setRowTransition(boolean animate, float translationX) {
		setRowTransition(animate, translationX, 1);
	}

	private void setRowTransition(boolean animate, float translationX, float alpha) {
		AnimatorListenerAdapter listener = new AnimatorListenerAdapter() {
			@Override
			public void onAnimationStart(Animator animation) {
				super.onAnimationStart(animation);

				if (mRowContainer != null) {
					mRowContainer.animationInProgress = true;
				}
			}

			@Override
			public void onAnimationEnd(Animator animation) {
				super.onAnimationEnd(animation);

				if (mRowContainer != null) {
					mRowContainer.animationInProgress = false;
				}

				tearDownRowContainerData();
			}
		};

		setRowTransition(animate, translationX, alpha, listener);
	}

	private void setRowTransition(boolean animate, float translationX, float alpha, @NonNull AnimatorListenerAdapter listener) {
		if (mRowContainer == null || mRowContainer.getCurrentSwipingView() == null) {
			return;
		}

		if (!mRowContainer.animationInProgress) {
			View currentSwipingView = mRowContainer.getCurrentSwipingView();
			if (animate) {
				ViewPropertyAnimator viewPropertyAnimator = currentSwipingView.animate();
				viewPropertyAnimator.setDuration(mAnimationTime);
				viewPropertyAnimator.translationX(translationX);

				if (mAlphaAnimationEnabled) {
					viewPropertyAnimator.alpha(alpha);
				}
				viewPropertyAnimator.setListener(listener);
			} else {
				currentSwipingView.setTranslationX(translationX);

				if (mAlphaAnimationEnabled) {
					currentSwipingView.setAlpha(alpha);
				}
			}
		}
	}

	private void tearDownRowContainerData() {
		if (mVelocityTracker != null) {
			mVelocityTracker.recycle();
		}

		mVelocityTracker = null;
		mDownX = 0;
		mDownY = 0;
		mRowContainer = null;
		mDownPosition = ListView.INVALID_POSITION;
		mSwiping = false;
	}

	class PendingDismissData implements Comparable<PendingDismissData> {
		public int position;

		public RowContainer rowContainer;

		public PendingDismissData(int position, RowContainer rowContainer) {
			this.position = position;
			this.rowContainer = rowContainer;
		}

		@Override
		public int compareTo(@NonNull PendingDismissData other) {
			// Sort by descending position
			return other.position - position;
		}
	}

	private void performDismiss(RowContainer dismissView, int dismissPosition) {
		// Animate the dismissed list item to zero-height and fire the dismiss callback when
		// all dismissed list item animations have completed. This triggers layout on each animation
		// frame; in the future we may want to do something smarter and more performant.
		if (mPendingDismiss != null) {
			boolean dismissingDifferentRow = mPendingDismiss.position != dismissPosition;
			int newPosition = mPendingDismiss.position < dismissPosition ? dismissPosition - 1 : dismissPosition;
			processPendingDismisses();
			if (dismissingDifferentRow) {
				addPendingDismiss(dismissView, newPosition);
			}
		} else {
			addPendingDismiss(dismissView, dismissPosition);
		}
	}

	private void addPendingDismiss(RowContainer dismissView, int dismissPosition) {
		dismissView.dismissState = DismissState.PENDING_DISMISS;

		if (dismissView.direction == SwipeDirection.FROM_RIGHT) {
			dismissView.rightUndoContainer.setVisibility(View.VISIBLE);
			dismissView.rightUpToDismissContainer.setVisibility(View.GONE);
		} else if (dismissView.direction == SwipeDirection.FROM_LEFT) {
			dismissView.leftUndoContainer.setVisibility(View.VISIBLE);
			dismissView.leftUpToDismissContainer.setVisibility(View.GONE);
		}

		mPendingDismiss = new PendingDismissData(dismissPosition, dismissView);
		// Notify the callbacks
		mCallbacks.onPendingDismiss(mRecyclerView, dismissPosition, dismissView.direction);
		// Automatically dismiss the item after a certain delay
		if (mDismissDelayMillis >= 0L) {
			mHandler.removeCallbacks(mDismissRunnable);
			mHandler.postDelayed(mDismissRunnable, mDismissDelayMillis);
		}
	}

	/**
	 * If a view was dismissed and the undo container is showing it will proceed with the final dismiss of the item.
	 *
	 * @return whether there were any pending rows to be dismissed.
	 */
	public boolean processPendingDismisses() {
		boolean existPendingDismisses = existPendingDismisses();
		if (existPendingDismisses)
			processPendingDismisses(mPendingDismiss);
		return existPendingDismisses;
	}

	/**
	 * Whether a row has been dismissed and is waiting for confirmation
	 *
	 * @return whether there are any pending rows to be dismissed.
	 */
	public boolean existPendingDismisses() {
		return mPendingDismiss != null && (mPendingDismiss.rowContainer.dismissState == DismissState.PENDING_DISMISS);
	}

	/**
	 * If a view was dismissed and the undo container is showing it will undo and make the data container reappear.
	 *
	 * @return whether there were any pending rows to be dismissed.
	 */
	public boolean undoPendingDismiss() {
		boolean existPendingDismisses = existPendingDismisses();
		if (existPendingDismisses) {
			if (mPendingDismiss.rowContainer.direction == SwipeDirection.FROM_RIGHT) {
				mPendingDismiss.rowContainer.rightUndoContainer.setVisibility(View.GONE);
			} else if (mPendingDismiss.rowContainer.direction == SwipeDirection.FROM_LEFT) {
				mPendingDismiss.rowContainer.leftUndoContainer.setVisibility(View.GONE);
			}

			ViewPropertyAnimator viewPropertyAnimator = mPendingDismiss.rowContainer.dataContainer.animate().translationX(0);
			if (mAlphaAnimationEnabled) {
				viewPropertyAnimator.alpha(1);
			}
			viewPropertyAnimator.setDuration(mAnimationTime).setListener(null);
			mPendingDismiss = null;
		}
		return existPendingDismisses;
	}

	private void processPendingDismisses(final PendingDismissData pendingDismissData) {
		mPendingDismiss = null;
		final ViewGroup.LayoutParams lp = pendingDismissData.rowContainer.container.getLayoutParams();
		final int originalHeight = pendingDismissData.rowContainer.container.getHeight();

		ValueAnimator animator = ValueAnimator.ofInt(originalHeight, 1).setDuration(mAnimationTime);

		animator.addListener(new AnimatorListenerAdapter() {
			@Override
			public void onAnimationEnd(Animator animation) {
				if (mCallbacks.canDismiss(pendingDismissData.position, pendingDismissData.rowContainer.direction))
					mCallbacks.onDismiss(mRecyclerView, pendingDismissData.position, pendingDismissData.rowContainer.direction);
				pendingDismissData.rowContainer.dataContainer.post(new Runnable() {
					@Override
					public void run() {
						pendingDismissData.rowContainer.dataContainer.setTranslationX(0);

						if (mAlphaAnimationEnabled) {
							pendingDismissData.rowContainer.dataContainer.setAlpha(1);
						}

						if (pendingDismissData.rowContainer.direction == SwipeDirection.FROM_RIGHT) {
							pendingDismissData.rowContainer.rightUndoContainer.setVisibility(View.GONE);
							pendingDismissData.rowContainer.rightUndoContainer.setTranslationX(0);

							if (mAlphaAnimationEnabled) {
								pendingDismissData.rowContainer.rightUndoContainer.setAlpha(1);
							}
						} else if (pendingDismissData.rowContainer.direction == SwipeDirection.FROM_LEFT) {
							pendingDismissData.rowContainer.leftUndoContainer.setVisibility(View.GONE);
							pendingDismissData.rowContainer.leftUndoContainer.setTranslationX(0);

							if (mAlphaAnimationEnabled) {
								pendingDismissData.rowContainer.leftUndoContainer.setAlpha(1);
							}
						}

						lp.height = originalHeight;
						pendingDismissData.rowContainer.container.setLayoutParams(lp);

						tearDownRowContainerData();
						setEnabled(true); // Fix problem with disabling in ScrollListener
					}
				});
			}
		});

		animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
			@Override
			public void onAnimationUpdate(ValueAnimator valueAnimator) {
				lp.height = (Integer) valueAnimator.getAnimatedValue();
				pendingDismissData.rowContainer.container.setLayoutParams(lp);
			}
		});

		animator.start();
	}
}
