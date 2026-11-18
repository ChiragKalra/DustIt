package com.bruhascended.dustit

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.bruhascended.dustit.ui.DustItRoot
import com.bruhascended.dustit.ui.theme.DustItTheme

class MainActivity : ComponentActivity() {
    /** Bucket a notification asked us to open; consumed once the review screen is shown. */
    private val bucketToOpen = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) bucketToOpen.value = intent.getStringExtra(EXTRA_OPEN_BUCKET)
        setContent {
            DustItTheme {
                DustItRoot(
                    bucketToOpen = bucketToOpen.value,
                    onBucketOpened = { bucketToOpen.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_OPEN_BUCKET)?.let { bucketToOpen.value = it }
    }

    companion object {
        private const val EXTRA_OPEN_BUCKET = "open_bucket"

        fun openBucketIntent(context: Context, bucketKey: String): Intent =
            Intent(context, MainActivity::class.java).putExtra(EXTRA_OPEN_BUCKET, bucketKey)
    }
}
