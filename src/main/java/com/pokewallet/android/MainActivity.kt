package com.pokewallet.android

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.pokewallet.R
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    lateinit var viewModel: WalletViewModel

    private var splashDone = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewModel = ViewModelProvider(this)[WalletViewModel::class.java]

        showFragment(SplashFragment())
    }

    /** Chamado pela SplashFragment (toque na tela ou timeout) — só a partir
     *  daqui o app passa a rotear pra Setup/Mochila conforme walletState. */
    fun advancePastSplash() {
        if (splashDone) return
        splashDone = true

        lifecycleScope.launch {
            viewModel.walletState.collectLatest { state ->
                when (state) {
                    is WalletState.NoWallet  -> showFragment(SetupFragment())
                    is WalletState.Created   -> showFragment(SetupFragment())
                    is WalletState.Loaded    -> showFragment(WalletFragment())
                    is WalletState.Error     -> showFragment(SetupFragment())
                    else -> { /* Loading/Creating – fragments handle their own loading state */ }
                }
            }
        }
    }

    private fun showFragment(fragment: Fragment) {
        val tag = fragment::class.java.simpleName
        if (supportFragmentManager.findFragmentByTag(tag) != null &&
            supportFragmentManager.findFragmentByTag(tag)?.isVisible == true) return

        supportFragmentManager.commit {
            replace(R.id.fragment_container, fragment, tag)
        }
    }
}
