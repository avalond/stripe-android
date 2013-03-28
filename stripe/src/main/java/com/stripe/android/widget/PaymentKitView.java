package com.stripe.android.widget;

import java.util.HashSet;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.Layout;
import android.text.Spanned;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.Animation.AnimationListener;
import android.view.animation.AnimationUtils;
import android.view.animation.DecelerateInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.stripe.android.R;
import com.stripe.android.model.Card;
import com.stripe.android.util.CardExpiry;
import com.stripe.android.util.CardNumberFormatter;
import com.stripe.android.util.TextUtils;

public class PaymentKitView extends FrameLayout {
    private static final long SLIDING_DURATION_MS = 500;

    private ImageView cardImageView;
    private ClippingEditText cardNumberView;
    private EditText expiryView;
    private EditText cvcView;

    private float cardNumberSlidingDelta = 0;
    private boolean isCardNumberCollapsed = false;

    private Card card = new Card(null, null, null, null);
    private int textColor;
    private int errorColor;

    private OnKeyListener emptyListener = new EmptyOnKeyListener();

    private InputFilter cvcEmptyFilter = new CvcEmptyFilter();
    private InputFilter[] cvcLengthOf3 = new InputFilter[] {
            new InputFilter.LengthFilter(3), cvcEmptyFilter };
    private InputFilter[] cvcLengthOf4 = new InputFilter[] {
            new InputFilter.LengthFilter(4), cvcEmptyFilter };

    private boolean lastValidationResult = false;

    private HashSet<OnValidationChangeListener> listeners
        = new HashSet<OnValidationChangeListener>();

    private Animation fadeInAnimation;

    private int minWidth;

    public void registerListener(OnValidationChangeListener listener) {
        listeners.add(listener);
    }

    public void unregisterListener(OnValidationChangeListener listener) {
        listeners.remove(listener);
    }

    public interface OnValidationChangeListener {
        public void onChange(boolean valid);
    }

    public PaymentKitView(Context context) {
        super(context);
        init();
    }

    public PaymentKitView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public PaymentKitView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init();
    }

    public Card getCard() {
        return card;
    }

    private void init() {
        LayoutInflater inflater = (LayoutInflater) getContext()
                .getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        View parent = inflater.inflate(R.layout.__pk_view, this);

        cardImageView = (ImageView) parent.findViewById(R.id.__pk_card_image);
        cardNumberView = (ClippingEditText) parent.findViewById(R.id.__pk_card_number);
        expiryView = (EditText) parent.findViewById(R.id.__pk_expiry);
        cvcView = (EditText) parent.findViewById(R.id.__pk_cvc);

        cardNumberView.setRawInputType(InputType.TYPE_CLASS_NUMBER);

        textColor = cvcView.getCurrentTextColor();
        errorColor = getContext().getResources().getColor(R.color.__pk_error_color);
        computeMinWidth();

        cardImageView.setTag(R.drawable.__pk_placeholder);
        fadeInAnimation = AnimationUtils.loadAnimation(getContext(), R.anim.fade_in);
    }

    private void computeMinWidth() {
        Rect bounds = new Rect();
        Paint textPaint = cardNumberView.getPaint();
        textPaint.getTextBounds("4", 0, 1, bounds);    // widest digit
        int cardNumberMinWidth = bounds.width() * 21;  // wide enough for 21 digits

        int marginLeft = getContext().getResources().getDimensionPixelSize(
                R.dimen.__pk_margin_left);

        minWidth = getPaddingLeft() + marginLeft + cardNumberMinWidth + getPaddingRight();
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();

        card.setNumber(cardNumberView.getText().toString());

        CardExpiry cardExpiry = new CardExpiry();
        cardExpiry.updateFromString(expiryView.getText().toString());
        card.setExpMonth(cardExpiry.getMonth());
        card.setExpYear(cardExpiry.getYear());

        card.setCVC(TextUtils.nullIfBlank(cvcView.getText().toString()));

        updateFields(false);
        notifyValidationChange();

        cardImageView.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                if (isCardNumberCollapsed) {
                    expandCardNumber();
                } else {
                    collapseCardNumber(true);
                }
            }
        });

        setupTextWatchers();

        if (card.validateNumber()) {
            if (card.validateExpiryDate()) {
                cvcView.requestFocus();
            } else {
                expiryView.requestFocus();
            }
        }
    }

    @Override
    public void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        // We want to be at least minWidth wide
        int width = Math.max(minWidth, getMeasuredWidth());

        if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.AT_MOST) {
            // If the measure spec gives us an upper bound, do a tight fit.
            width = Math.min(minWidth, getMeasuredWidth());
        }

        setMeasuredDimension(width, getMeasuredHeight());
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        cardImageView.setEnabled(enabled);
        cardNumberView.setEnabled(enabled);
        expiryView.setEnabled(enabled);
        cvcView.setEnabled(enabled);
    }

    private void setupTextWatchers() {
        CardNumberWatcher cardNumberWatcher = new CardNumberWatcher();
        cardNumberView.setFilters(new InputFilter[] {
                new InputFilter.LengthFilter(19), cardNumberWatcher });
        cardNumberView.addTextChangedListener(cardNumberWatcher);
        cardNumberView.setOnFocusChangeListener(new OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus && isCardNumberCollapsed) {
                    expandCardNumber();
                }
            }
        });

        ExpiryWatcher expiryWatcher = new ExpiryWatcher();
        expiryView.setFilters(new InputFilter[] { expiryWatcher });
        expiryView.addTextChangedListener(expiryWatcher);
        expiryView.setOnKeyListener(emptyListener);

        cvcView.setFilters(cvcLengthOf3);
        cvcView.addTextChangedListener(new CvcWatcher());
        cvcView.setOnKeyListener(emptyListener);
        cvcView.setOnFocusChangeListener(new OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    updateCvcType();
                } else {
                    updateCardType();
                }
            }
        });
    }

    private void updateFields(boolean animate) {
        cardNumberView.setTextColor(textColor);
        if (card.validateNumberLength()) {
            if (card.validateNumber()) {
                collapseCardNumber(animate);
            } else {
                cardNumberView.setTextColor(errorColor);
            }
        }

        if ("American Express".equals(card.getType())) {
            cvcView.setFilters(cvcLengthOf4);
        } else {
            cvcView.setFilters(cvcLengthOf3);
        }

        updateCardType();
    }

    private void computeCardNumberSlidingDelta() {
        Layout layout = cardNumberView.getLayout();
        if (layout == null) {
            return;
        }

        String number = cardNumberView.getText().toString();
        card.setNumber(number);
        int suffixLength = "American Express".equals(card.getType()) ? 5 : 4;

        cardNumberSlidingDelta = layout.getPrimaryHorizontal(number.length() - suffixLength);
    }

    private void collapseCardNumber(boolean animate) {
        if (!card.validateNumber()) {
            return;
        }
        computeCardNumberSlidingDelta();
        isCardNumberCollapsed = true;
        if (animate) {
            animateCardNumber();
        } else {
            showExpiryAndCvc();
        }
    }

    private void expandCardNumber() {
        isCardNumberCollapsed = false;
        animateCardNumber();
    }

    private void animateCardNumber() {
        float fromXDelta = isCardNumberCollapsed ? 0
                : cardNumberSlidingDelta;
        float toXDelta = isCardNumberCollapsed ? cardNumberSlidingDelta : 0;
        ClippingAnimation anim = new ClippingAnimation(cardNumberView, fromXDelta, toXDelta);
        anim.setDuration(SLIDING_DURATION_MS);
        anim.setAnimationListener(mAnimationListener);
        anim.setInterpolator(new DecelerateInterpolator());
        cardNumberView.startAnimation(anim);
    }

    private void showExpiryAndCvc() {
        cardNumberView.setClipX((int) cardNumberSlidingDelta);
        expiryView.setVisibility(View.VISIBLE);
        cvcView.setVisibility(View.VISIBLE);
    }

    private AnimationListener mAnimationListener = new AnimationListener() {
        public void onAnimationEnd(Animation animation) {
            if (!isCardNumberCollapsed) {
                return;
            }
            showExpiryAndCvc();
            expiryView.requestFocus();
        }

        public void onAnimationRepeat(Animation animation) {
            // not needed
        }

        public void onAnimationStart(Animation animation) {
            if (isCardNumberCollapsed) {
                return;
            }

            expiryView.setVisibility(View.GONE);
            cvcView.setVisibility(View.GONE);
        }
    };

    private void notifyValidationChange() {
        boolean valid = card.validateCard();
        if (valid != lastValidationResult) {
            for (OnValidationChangeListener listener : listeners) {
                listener.onChange(valid);
            }
        }
        lastValidationResult = valid;
    }

    private int getImageResourceForCardType() {
        String cardType = card.getType();

        if ("American Express".equals(cardType)) {
            return R.drawable.__pk_amex;
        }

        if ("Discover".equals(cardType)) {
            return R.drawable.__pk_discover;
        }

        if ("JCB".equals(cardType)) {
            return R.drawable.__pk_jcb;
        }

        if ("Diners Club".equals(cardType)) {
            return R.drawable.__pk_diners;
        }

        if ("Visa".equals(cardType)) {
            return R.drawable.__pk_visa;
        }

        if ("MasterCard".equals(cardType)) {
            return R.drawable.__pk_mastercard;
        }

        return R.drawable.__pk_placeholder;
    }

    private void updateCardType() {
        int resId = getImageResourceForCardType();
        updateCardImage(resId);
    }

    private void updateCvcType() {
        boolean isAmex = "American Express".equals(card.getType());
        int resId = isAmex ? R.drawable.__pk_cvc_amex : R.drawable.__pk_cvc;
        updateCardImage(resId);
    }

    private void updateCardImage(int resId) {
        Integer oldResId = (Integer) cardImageView.getTag();
        if (oldResId == resId) {
            return;
        }
        cardImageView.setTag(resId);
        cardImageView.setImageResource(resId);
        cardImageView.startAnimation(fadeInAnimation);
    }

    @Override
    public Parcelable onSaveInstanceState() {
        Parcelable superState = super.onSaveInstanceState();
        final SavedState myState = new SavedState(superState);
        myState.cardNumberSlidingDelta = cardNumberSlidingDelta;
        return myState;
    }

    @Override
    public void onRestoreInstanceState(Parcelable state) {
        if (!state.getClass().equals(SavedState.class)) {
            // Didn't save state for us in onSaveInstanceState
            super.onRestoreInstanceState(state);
            return;
        }

        // Restore the instance state
        SavedState myState = (SavedState) state;
        super.onRestoreInstanceState(myState.getSuperState());
        cardNumberSlidingDelta = myState.cardNumberSlidingDelta;
    }

    // Save cardNumberSlidingDelta so we can update the layout for the card number field during
    // onAttachWindow, when the layout of the EditText is not yet initialized.
    private static class SavedState extends BaseSavedState {
        float cardNumberSlidingDelta;

        public SavedState(Parcel source) {
            super(source);
            cardNumberSlidingDelta = source.readFloat();
        }

        public SavedState(Parcelable superState) {
            super(superState);
        }

        public void writeToParcel(Parcel dest, int flags) {
            super.writeToParcel(dest, flags);
            dest.writeFloat(cardNumberSlidingDelta);
        }

        @SuppressWarnings("unused")
        public static final Parcelable.Creator<SavedState> CREATOR
            = new Parcelable.Creator<SavedState>() {
            public SavedState createFromParcel(Parcel in) {
                return new SavedState(in);
            }

            public SavedState[] newArray(int size) {
                return new SavedState[size];
            }
        };
    }

    private class CardNumberWatcher implements InputFilter, TextWatcher {
        private boolean isUserInput = true;
        private boolean isInserting = false;

        private boolean isAllowed(char c) {
            if (c >= '0' && c <= '9') {
                return true;
            }
            return (c == ' ');
        }

        @Override
        public CharSequence filter(CharSequence source, int start, int end,
                Spanned dest, int dstart, int dend) {
            for (int i = start; i < end; ++i) {
                if (!isAllowed(source.charAt(i))) {
                    return "";
                }
            }
            return null;
        }

        @Override
        public void afterTextChanged(Editable s) {
            String number = s.toString();

            String formattedNumber = CardNumberFormatter.format(number, isInserting);
            if (!number.equals(formattedNumber)) {
                isUserInput = false;
                s.replace(0, s.length(), formattedNumber);
                return;
            }

            card.setNumber(number);
            updateFields(true);

            notifyValidationChange();

            isUserInput = true;
        }
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            if (isUserInput) {
                isInserting = (after > count);
            }
        }
        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
            // not needed
        }
    }

    private class ExpiryWatcher implements InputFilter, TextWatcher {
        private static final int EXPIRY_MAX_LENGTH = 5;

        private CardExpiry cardExpiry = new CardExpiry();
        private boolean isInserting = false;

        @Override
        public CharSequence filter(CharSequence source, int start, int end,
                Spanned dest, int dstart, int dend) {
            StringBuffer buf = new StringBuffer();
            buf.append(dest.subSequence(0, dstart));
            buf.append(source.subSequence(start, end));
            buf.append(dest.subSequence(dend, dest.length()));
            String str = buf.toString();
            if (str.length() == 0) {
                // Jump to previous field when user empties this one
                cardNumberView.requestFocus();
                return null;
            }
            if (str.length() > EXPIRY_MAX_LENGTH) {
                return "";
            }
            cardExpiry.updateFromString(str);
            return cardExpiry.isPartiallyValid() ? null : "";
        }

        @Override
        public void afterTextChanged(Editable s) {
            String str = s.toString();
            cardExpiry.updateFromString(str);
            card.setExpMonth(cardExpiry.getMonth());
            card.setExpYear(cardExpiry.getYear());
            if (cardExpiry.isPartiallyValid()) {
                String formattedString = isInserting ?
                        cardExpiry.toStringWithTrail() : cardExpiry.toString();
                if (!str.equals(formattedString)) {
                    s.replace(0, s.length(), formattedString);
                }
            }
            if (cardExpiry.isValid()) {
                cvcView.requestFocus();
            }

            notifyValidationChange();
        }
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            isInserting = (after > count);
        }
        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
            // not needed
        }
    }

    private class CvcWatcher implements TextWatcher {
        @Override
        public void afterTextChanged(Editable s) {
            card.setCVC(TextUtils.nullIfBlank(s.toString()));
            notifyValidationChange();
        }
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            // not needed
        }
        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
            // not needed
        }
    }

    // Jump to previous field when user hits backspace on an empty field
    private class EmptyOnKeyListener implements OnKeyListener {
        @Override
        public boolean onKey(View v, int keyCode, KeyEvent event) {
            if (keyCode != KeyEvent.KEYCODE_DEL) {
                return false;
            }
            if (event.getAction() != KeyEvent.ACTION_UP) {
                return false;
            }
            if (v.getId() == R.id.__pk_expiry) {
                EditText editText = (EditText) v;
                if (editText.getText().length() == 0) {
                    cardNumberView.requestFocus();
                    return false;
                }
            }
            if (v.getId() == R.id.__pk_cvc) {
                EditText editText = (EditText) v;
                if (editText.getText().length() == 0) {
                    expiryView.requestFocus();
                    return false;
                }
            }
            return false;
        }
    };

    // Jump to expiry date field when user empties the cvc field
    private class CvcEmptyFilter implements InputFilter {
        @Override
        public CharSequence filter(CharSequence source, int start, int end,
                Spanned dest, int dstart, int dend) {
            int n = (dstart - 0) + (end - start) + (dest.length() - dend);
            if (n == 0) {
                expiryView.requestFocus();
            }
            return null;
        }
    }
}