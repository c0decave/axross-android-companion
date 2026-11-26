package io.github.c0decave.axross.companion;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The "write a message" screen Android requires of a default SMS app
 * (SENDTO sms:/smsto:/mms:/mmsto:). The companion writes nothing for other
 * apps - every send comes from axross - so it says so and closes. Nothing
 * of the intent (number, text) is read.
 */
public final class ComposeActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad, pad, pad);
        box.setFitsSystemWindows(true);
        TextView text = new TextView(this);
        text.setText(R.string.compose_refused);
        text.setTextSize(16);
        box.addView(text);
        Button close = new Button(this);
        close.setText(R.string.close);
        close.setOnClickListener(v -> finish());
        box.addView(close);
        setContentView(box);
    }
}
