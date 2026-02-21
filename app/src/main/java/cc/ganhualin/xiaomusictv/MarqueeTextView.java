package cc.ganhualin.xiaomusictv;

import android.content.Context;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.view.Choreographer;
import androidx.appcompat.widget.AppCompatTextView;

public class MarqueeTextView extends AppCompatTextView {

    private float mOffset = 0;
    private boolean mRunning = false;
    private long mLastTime = 0;
    
    private static final float SPEED_PX_PER_SEC = 100f;  // Optimal speed for readability
    private static final float PAUSE_DURATION_MS = 1500f; // 1.5s initial pause to read it
    private static final float GAP = 150f; // Empty gap before the copy loops in
    
    private int mState = 0; // 0=Initial Pause, 1=Scrolling
    private float mPauseTimer = 0;

    private final Choreographer.FrameCallback mCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!mRunning) return;
            long now = System.currentTimeMillis();
            if (mLastTime == 0) mLastTime = now;
            float dt = (now - mLastTime) / 1000f;
            mLastTime = now;
            
            step(dt);
            invalidate(); // Trigger onDraw
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    public MarqueeTextView(Context context) {
        super(context);
        init();
    }

    public MarqueeTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }
    
    public MarqueeTextView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }
    
    private void init() {
        setMaxLines(1);
    }

    @Override
    protected void onTextChanged(CharSequence text, int start, int lengthBefore, int lengthAfter) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter);
        reset();
    }
    
    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        reset();
    }

    private void reset() {
        mOffset = 0;
        mState = 0;
        mPauseTimer = PAUSE_DURATION_MS;
        mLastTime = 0; // Reset timer so dt doesn't jump
        
        String t = getText() != null ? getText().toString() : "";
        float textW = getPaint().measureText(t);
        float viewW = getWidth() - getPaddingLeft() - getPaddingRight();
        
        if (textW > viewW && viewW > 0) {
            if (!mRunning) {
                mRunning = true;
                Choreographer.getInstance().postFrameCallback(mCallback);
            }
        } else {
            mRunning = false;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mRunning = false;
    }
    
    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        reset();
    }

    private void step(float dt) {
        if (mState == 0) {
            // Paused
            mPauseTimer -= dt * 1000f;
            if (mPauseTimer <= 0) {
                mState = 1;
            }
        } else {
            // Scrolling
            mOffset += SPEED_PX_PER_SEC * dt;
            
            String t = getText() != null ? getText().toString() : "";
            float textW = getPaint().measureText(t);
            float totalWidth = textW + GAP;
            
            // Seamless looping: When offset reaches the length of text+gap, we subtract it.
            // This perfectly bumps Copy 2 into the visual position of Copy 1.
            if (mOffset >= totalWidth) {
                mOffset -= totalWidth; 
            }
        }
    }
    
    @Override
    protected void onDraw(Canvas canvas) {
        CharSequence txt = getText();
        if (txt == null || txt.length() == 0) {
            super.onDraw(canvas);
            return;
        }
        
        float textW = getPaint().measureText(txt.toString());
        float viewW = getWidth() - getPaddingLeft() - getPaddingRight();
        
        // Let it handle gravity=center naturally if it fits
        if (textW <= viewW || viewW <= 0 || !mRunning) {
            super.onDraw(canvas);
            return;
        }
        
        int paddingLeft = getPaddingLeft();
        int paddingTop = getPaddingTop();
        int paddingRight = getPaddingRight();
        int paddingBottom = getPaddingBottom();
        
        // Add clipping bounds to prevent text spilling
        canvas.save();
        canvas.clipRect(paddingLeft, paddingTop, getWidth() - paddingRight, getHeight() - paddingBottom);

        android.text.Layout layout = getLayout();
        if (layout == null) {
            super.onDraw(canvas);
            canvas.restore();
            return;
        }

        // TextView text bounds within padding area
        canvas.translate(paddingLeft, paddingTop);
        
        float startXOffset = 0;
        int gravityHorizontal = getGravity() & android.view.Gravity.HORIZONTAL_GRAVITY_MASK;
        if (gravityHorizontal == android.view.Gravity.CENTER_HORIZONTAL || gravityHorizontal == android.view.Gravity.CENTER) {
             startXOffset = (viewW - textW) / 2f;
        }

        if (mState == 0) {
            // Static Pause at beginning: Draw aligned to left (plus gravity offset if centered)
            canvas.save();
            canvas.translate(startXOffset, 0);
            layout.draw(canvas);
            canvas.restore();
        } else {
            // Scrolling
            // Draw Copy 1
            canvas.save();
            canvas.translate(startXOffset - mOffset, 0);
            layout.draw(canvas);
            canvas.restore();
            
            // Draw Copy 2 (Gap + Offset)
            canvas.save();
            canvas.translate(startXOffset - mOffset + textW + GAP, 0);
            layout.draw(canvas);
            canvas.restore();
        }

        canvas.restore();
    }
}
