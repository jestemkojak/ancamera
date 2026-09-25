package com.ancamera

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/** Placeholder until Task 5. */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "ancamera" })
    }
}
