package lv.budseq;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

/** Кнопки в строку с переносом — ничего не обрезается на узких экранах и длинных переводах. */
public class FlowLayout extends ViewGroup {

    public FlowLayout(Context c) {
        super(c);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int maxW = MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight();
        boolean unbounded = MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED;
        int x = 0, y = 0, lineH = 0, widest = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View ch = getChildAt(i);
            if (ch.getVisibility() == GONE) continue;
            measureChildWithMargins(ch, widthSpec, 0, heightSpec, 0);
            MarginLayoutParams lp = (MarginLayoutParams) ch.getLayoutParams();
            int cw = ch.getMeasuredWidth() + lp.leftMargin + lp.rightMargin;
            int chh = ch.getMeasuredHeight() + lp.topMargin + lp.bottomMargin;
            if (!unbounded && x > 0 && x + cw > maxW) {
                y += lineH;
                x = 0;
                lineH = 0;
            }
            x += cw;
            lineH = Math.max(lineH, chh);
            widest = Math.max(widest, x);
        }
        int w = unbounded ? widest + getPaddingLeft() + getPaddingRight() : MeasureSpec.getSize(widthSpec);
        int h = y + lineH + getPaddingTop() + getPaddingBottom();
        setMeasuredDimension(resolveSize(w, widthSpec), resolveSize(h, heightSpec));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int maxW = r - l - getPaddingLeft() - getPaddingRight();
        int x = 0, y = 0, lineH = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View ch = getChildAt(i);
            if (ch.getVisibility() == GONE) continue;
            MarginLayoutParams lp = (MarginLayoutParams) ch.getLayoutParams();
            int cw = ch.getMeasuredWidth() + lp.leftMargin + lp.rightMargin;
            int chh = ch.getMeasuredHeight() + lp.topMargin + lp.bottomMargin;
            if (x > 0 && x + cw > maxW) {
                y += lineH;
                x = 0;
                lineH = 0;
            }
            int cl = getPaddingLeft() + x + lp.leftMargin;
            int ct = getPaddingTop() + y + lp.topMargin;
            ch.layout(cl, ct, cl + ch.getMeasuredWidth(), ct + ch.getMeasuredHeight());
            x += cw;
            lineH = Math.max(lineH, chh);
        }
    }

    @Override
    protected LayoutParams generateDefaultLayoutParams() {
        return new MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
    }

    @Override
    public LayoutParams generateLayoutParams(AttributeSet attrs) {
        return new MarginLayoutParams(getContext(), attrs);
    }

    @Override
    protected LayoutParams generateLayoutParams(LayoutParams p) {
        return p instanceof MarginLayoutParams ? new MarginLayoutParams((MarginLayoutParams) p) : new MarginLayoutParams(p);
    }

    @Override
    protected boolean checkLayoutParams(LayoutParams p) {
        return p instanceof MarginLayoutParams;
    }
}
