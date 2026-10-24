package com.khalsatorrent.cs3builder

import android.os.Bundle
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.badge.BadgeDrawable
import com.khalsatorrent.cs3builder.databinding.ActivityMainBinding
import com.khalsatorrent.cs3builder.network.LogRepository
import com.khalsatorrent.cs3builder.ui.AppsFragment
import com.khalsatorrent.cs3builder.ui.BrowserFragment
import com.khalsatorrent.cs3builder.ui.CodeFragment
import com.khalsatorrent.cs3builder.ui.LogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private lateinit var browserFragment: BrowserFragment
    private lateinit var appsFragment: AppsFragment
    private lateinit var logFragment: LogFragment
    private lateinit var codeFragment: CodeFragment
    private lateinit var activeFragment: Fragment

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        ViewCompat.setOnApplyWindowInsetsListener(binding.appBarLayout) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, 0)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.bottomNav) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, 0, bars.right, bars.bottom)
            insets
        }
        // Keep the URL bar / inputs above the keyboard
        ViewCompat.setOnApplyWindowInsetsListener(binding.fragmentContainer) { v, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val nav = binding.bottomNav.height
            v.setPadding(0, 0, 0, if (ime.bottom > 0) (ime.bottom - bars.bottom - nav).coerceAtLeast(0) else 0)
            insets
        }

        // Re-attach existing fragments after process death / recreation instead of creating duplicates
        val fm = supportFragmentManager
        browserFragment = fm.findFragmentByTag(TAG_BROWSER) as? BrowserFragment ?: BrowserFragment()
        appsFragment = fm.findFragmentByTag(TAG_APPS) as? AppsFragment ?: AppsFragment()
        logFragment = fm.findFragmentByTag(TAG_LOGS) as? LogFragment ?: LogFragment()
        codeFragment = fm.findFragmentByTag(TAG_CODE) as? CodeFragment ?: CodeFragment()

        if (savedInstanceState == null) {
            fm.beginTransaction().apply {
                add(R.id.fragmentContainer, browserFragment, TAG_BROWSER)
                add(R.id.fragmentContainer, appsFragment, TAG_APPS).hide(appsFragment)
                add(R.id.fragmentContainer, logFragment, TAG_LOGS).hide(logFragment)
                add(R.id.fragmentContainer, codeFragment, TAG_CODE).hide(codeFragment)
            }.commitNow()
            activeFragment = browserFragment
        } else {
            activeFragment = listOf(browserFragment, appsFragment, logFragment, codeFragment).firstOrNull { !it.isHidden } ?: browserFragment
        }
        updateTitle()

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_browser -> showFragment(browserFragment)
                R.id.nav_apps -> showFragment(appsFragment)
                R.id.nav_logs -> showFragment(logFragment)
                R.id.nav_code -> showFragment(codeFragment)
                else -> return@setOnItemSelectedListener false
            }
            true
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    activeFragment === browserFragment && browserFragment.handleBack() -> Unit
                    activeFragment !== browserFragment -> binding.bottomNav.selectedItemId = R.id.nav_browser
                    else -> { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
                }
            }
        })

        // Live request counter on the Logs tab
        val badge: BadgeDrawable = binding.bottomNav.getOrCreateBadge(R.id.nav_logs)
        badge.isVisible = false
        LogRepository.logs.observe(this) { logs ->
            badge.isVisible = logs.isNotEmpty()
            badge.number = logs.size
            badge.maxCharacterCount = 4
        }

        if (savedInstanceState == null && LogRepository.size() == 0) restoreLastSession()
    }

    private fun restoreLastSession() {
        lifecycleScope.launch {
            val restored = withContext(Dispatchers.IO) { LogRepository.restoreSession(applicationContext) }
            if (restored > 0) Toast.makeText(this@MainActivity, "Restored $restored requests from your last session", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onStop() {
        super.onStop()
        val ctx = applicationContext
        Thread { LogRepository.saveSession(ctx) }.start()
    }

    private fun showFragment(fragment: Fragment) {
        if (fragment === activeFragment) return
        supportFragmentManager.beginTransaction().hide(activeFragment).show(fragment).commitNow()
        activeFragment = fragment
        updateTitle()
        invalidateOptionsMenu()
    }

    private fun updateTitle() {
        supportActionBar?.title = when (activeFragment) {
            appsFragment -> "Capture an App"
            logFragment -> "Network Logs"
            codeFragment -> "Generated Extension"
            else -> getString(R.string.app_name)
        }
    }

    fun navigateToLogs() { binding.bottomNav.selectedItemId = R.id.nav_logs }

    fun navigateToBrowser(url: String? = null) {
        binding.bottomNav.selectedItemId = R.id.nav_browser
        if (url != null) browserFragment.open(url)
    }

    fun generateAndShowCode() {
        binding.bottomNav.selectedItemId = R.id.nav_code
        codeFragment.regenerate()
    }

    fun importIntoLogs() {
        binding.bottomNav.selectedItemId = R.id.nav_logs
        logFragment.launchImport()
    }

    companion object {
        private const val TAG_BROWSER = "browser"
        private const val TAG_APPS = "apps"
        private const val TAG_LOGS = "log"
        private const val TAG_CODE = "code"
    }
}
