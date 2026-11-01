package com.bookcover.diagnostic.testing

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import com.bookcover.diagnostic.rewards.RelicCoverView

/** A native-only generated-pixel host, absent from ordinary builds and never exported. */
class RelicRenderTestActivity : Activity() {
    internal lateinit var coverViewForTests: RelicCoverView
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(null)
        check(packageName == "com.bookcover.diagnostic.qa")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        coverViewForTests = RelicCoverView(this)
        setContentView(coverViewForTests)
    }

    override fun onDestroy() {
        if (::coverViewForTests.isInitialized) coverViewForTests.release()
        super.onDestroy()
    }
}
