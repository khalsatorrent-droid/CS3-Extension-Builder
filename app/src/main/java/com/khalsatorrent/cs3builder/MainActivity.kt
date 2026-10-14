package com.khalsatorrent.cs3builder

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.khalsatorrent.cs3builder.databinding.ActivityMainBinding
import com.khalsatorrent.cs3builder.model.CS3Template
import com.khalsatorrent.cs3builder.ui.BrowserFragment
import com.khalsatorrent.cs3builder.ui.CodeFragment
import com.khalsatorrent.cs3builder.ui.LogFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val browserFragment = BrowserFragment()
    private val logFragment = LogFragment()
    private val codeFragment = CodeFragment()

    private var activeFragment: Fragment = browserFragment

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().apply {
                add(R.id.fragmentContainer, browserFragment, "browser")
                add(R.id.fragmentContainer, logFragment, "log").hide(logFragment)
                add(R.id.fragmentContainer, codeFragment, "code").hide(codeFragment)
            }.commit()
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_browser -> {
                    showFragment(browserFragment)
                    supportActionBar?.title = getString(R.string.app_name)
                    true
                }
                R.id.nav_logs -> {
                    showFragment(logFragment)
                    supportActionBar?.title = "Network Logs"
                    true
                }
                R.id.nav_code -> {
                    showFragment(codeFragment)
                    supportActionBar?.title = "Generated Code"
                    true
                }
                else -> false
            }
        }
    }

    private fun showFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .hide(activeFragment)
            .show(fragment)
            .commit()
        activeFragment = fragment
    }

    fun navigateToCode(template: CS3Template) {
        codeFragment.displayTemplate(template)
        binding.bottomNav.selectedItemId = R.id.nav_code
    }

    override fun onBackPressed() {
        if (activeFragment is BrowserFragment) {
            val browser = activeFragment as BrowserFragment
            if (browser.canGoBack()) {
                browser.goBack()
                return
            }
        }
        super.onBackPressed()
    }
}
