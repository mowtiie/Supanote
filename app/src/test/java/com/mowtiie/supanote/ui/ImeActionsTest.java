package com.mowtiie.supanote.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;

import org.junit.Test;

public class ImeActionsTest {

    private static KeyEvent key(int keyCode, int action) {
        KeyEvent event = mock(KeyEvent.class);
        when(event.getKeyCode()).thenReturn(keyCode);
        when(event.getAction()).thenReturn(action);
        return event;
    }

    @Test
    public void keyboardDoneKeySubmits() {
        assertTrue(ImeActions.isSubmit(EditorInfo.IME_ACTION_DONE, null));
    }

    @Test
    public void hardwareEnterKeyDownSubmits() {
        assertTrue(ImeActions.isSubmit(EditorInfo.IME_NULL, key(KeyEvent.KEYCODE_ENTER, KeyEvent.ACTION_DOWN)));
    }

    @Test
    public void enterKeyUpDoesNotSubmitASecondTime() {
        assertFalse(ImeActions.isSubmit(EditorInfo.IME_NULL, key(KeyEvent.KEYCODE_ENTER, KeyEvent.ACTION_UP)));
    }

    @Test
    public void otherKeysAndActionsDoNotSubmit() {
        assertFalse(ImeActions.isSubmit(EditorInfo.IME_NULL, key(KeyEvent.KEYCODE_A, KeyEvent.ACTION_DOWN)));
        assertFalse(ImeActions.isSubmit(EditorInfo.IME_ACTION_NEXT, null));
        assertFalse(ImeActions.isSubmit(EditorInfo.IME_NULL, null));
    }
}
