package com.mowtiie.supanote.ui;

import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;

public final class ImeActions {

    private ImeActions() {
    }

    /**
     * True when an editor action should submit a form: the on-screen keyboard's Done key, or the Enter key of a
     * hardware keyboard (which arrives as IME_NULL together with the key event).
     */
    public static boolean isSubmit(int actionId, KeyEvent event) {
        if (actionId == EditorInfo.IME_ACTION_DONE) return true;
        return event != null
                && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                && event.getAction() == KeyEvent.ACTION_DOWN;
    }
}
