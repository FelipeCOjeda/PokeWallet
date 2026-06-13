package com.pokewallet.android

import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.TextInputEditText
import com.pokewallet.R
import com.pokewallet.crypto.Network
import com.pokewallet.crypto.WalletRestore
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class SetupFragment : Fragment() {

    private lateinit var viewModel: WalletViewModel

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_setup, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel = ViewModelProvider(requireActivity())[WalletViewModel::class.java]

        val btnCreate  = view.findViewById<MaterialButton>(R.id.btn_create_wallet)
        val btnRestore = view.findViewById<MaterialButton>(R.id.btn_restore_wallet)
        val progress   = view.findViewById<ProgressBar>(R.id.progress_setup)
        val tvStatus   = view.findViewById<TextView>(R.id.tv_setup_status)
        val cardError  = view.findViewById<View>(R.id.card_error)
        val tvError    = view.findViewById<TextView>(R.id.tv_error)

        btnCreate.setOnClickListener {
            viewModel.createWallet(Network.MAINNET)
        }

        btnRestore.setOnClickListener { showRestoreDialog() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.walletState.collectLatest { state ->
                when (state) {
                    is WalletState.Creating -> {
                        progress.visibility   = View.VISIBLE
                        tvStatus.visibility   = View.VISIBLE
                        btnCreate.isEnabled   = false
                        cardError.visibility  = View.GONE
                    }
                    is WalletState.Created -> {
                        progress.visibility   = View.GONE
                        tvStatus.visibility   = View.GONE
                        btnCreate.isEnabled   = true
                        showMnemonicDialog(state.mnemonic, state.passphrase)
                    }
                    is WalletState.Error -> {
                        progress.visibility   = View.GONE
                        tvStatus.visibility   = View.GONE
                        btnCreate.isEnabled   = true
                        tvError.text          = state.message
                        cardError.visibility  = View.VISIBLE
                    }
                    else -> {
                        progress.visibility   = View.GONE
                        tvStatus.visibility   = View.GONE
                        btnCreate.isEnabled   = true
                    }
                }
            }
        }
    }

    private fun showRestoreDialog() {
        val dialogView      = layoutInflater.inflate(R.layout.dialog_restore, null)
        val etMnemonic      = dialogView.findViewById<TextInputEditText>(R.id.et_mnemonic)
        val tvWordCount     = dialogView.findViewById<TextView>(R.id.tv_word_count)
        val etPassphrase    = dialogView.findViewById<TextInputEditText>(R.id.et_passphrase_restore)
        val chipGroup       = dialogView.findViewById<ChipGroup>(R.id.chip_group_network)
        val progressRestore = dialogView.findViewById<ProgressBar>(R.id.progress_restore)
        val tvError         = dialogView.findViewById<TextView>(R.id.tv_restore_error)
        val btnCancel       = dialogView.findViewById<MaterialButton>(R.id.btn_cancel_restore)
        val btnConfirm      = dialogView.findViewById<MaterialButton>(R.id.btn_confirm_restore)

        var selectedNetwork = Network.MAINNET

        chipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            selectedNetwork = if (checkedIds.firstOrNull() == R.id.chip_testnet)
                Network.TESTNET else Network.MAINNET
        }

        etMnemonic.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val words = s?.toString()?.trim()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() } ?: emptyList()
                val count = words.size
                tvWordCount.text = "$count / 24 palavras"
                tvWordCount.setTextColor(ContextCompat.getColor(requireContext(),
                    if (count == 24) R.color.green_status else R.color.text_secondary))
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        val dialog = AlertDialog.Builder(requireContext(), R.style.Theme_PokéWallet_Dialog)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        btnCancel.setOnClickListener {
            viewModel.resetRestoreState()
            dialog.dismiss()
        }

        btnConfirm.setOnClickListener {
            val raw   = etMnemonic.text?.toString()?.trim() ?: ""
            val words = raw.split(Regex("\\s+")).filter { it.isNotEmpty() }
            val passphrase = etPassphrase.text?.toString()?.trim() ?: ""

            if (words.size != 24) {
                tvError.text       = "Informe exatamente 24 palavras (${words.size} fornecidas)."
                tvError.visibility = View.VISIBLE
                return@setOnClickListener
            }

            val invalid = WalletRestore.invalidWords(words)
            if (invalid.isNotEmpty()) {
                tvError.text       = "Palavra(s) inválida(s): ${invalid.take(3).joinToString(", ")}${if (invalid.size > 3) "…" else ""}"
                tvError.visibility = View.VISIBLE
                return@setOnClickListener
            }

            tvError.visibility  = View.GONE
            progressRestore.visibility = View.VISIBLE
            btnConfirm.isEnabled = false
            btnCancel.isEnabled  = false

            viewModel.restoreWallet(words, passphrase, selectedNetwork)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.restoreState.collectLatest { state ->
                when (state) {
                    is RestoreState.Restoring -> {
                        progressRestore.visibility = View.VISIBLE
                        btnConfirm.isEnabled       = false
                    }
                    is RestoreState.Success -> {
                        dialog.dismiss()
                        viewModel.resetRestoreState()
                    }
                    is RestoreState.Error -> {
                        progressRestore.visibility = View.GONE
                        btnConfirm.isEnabled       = true
                        btnCancel.isEnabled        = true
                        tvError.text               = state.message
                        tvError.visibility         = View.VISIBLE
                        viewModel.resetRestoreState()
                    }
                    is RestoreState.Idle -> {}
                }
            }
        }

        dialog.show()
        dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
    }

    private fun showMnemonicDialog(mnemonic: String, passphrase: String) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_mnemonic, null)
        populateMnemonicGrid(dialogView, mnemonic)
        dialogView.findViewById<TextView>(R.id.tv_passphrase).text = passphrase

        val dialog = AlertDialog.Builder(requireContext(), R.style.Theme_PokéWallet_Dialog)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialogView.findViewById<MaterialButton>(R.id.btn_confirm_mnemonic).setOnClickListener {
            dialog.dismiss()
            showVerifyDialog(mnemonic, passphrase)
        }

        dialog.show()
    }

    private fun showVerifyDialog(mnemonic: String, passphrase: String) {
        val words = mnemonic.trim().split(" ")
        val pos1  = (0 until words.size).random()
        val pos2  = (0 until words.size).filter { it != pos1 }.random()
        val idx1  = minOf(pos1, pos2)
        val idx2  = maxOf(pos1, pos2)

        val dialogView = layoutInflater.inflate(R.layout.dialog_verify_seed, null)

        dialogView.findViewById<TextView>(R.id.tv_word1_label).text =
            "Qual é a palavra #${idx1 + 1}?"
        dialogView.findViewById<TextView>(R.id.tv_word2_label).text =
            "Qual é a palavra #${idx2 + 1}?"

        val etWord1   = dialogView.findViewById<TextInputEditText>(R.id.et_word1)
        val etWord2   = dialogView.findViewById<TextInputEditText>(R.id.et_word2)
        val etPokemon = dialogView.findViewById<TextInputEditText>(R.id.et_pokemon)
        val tvError   = dialogView.findViewById<TextView>(R.id.tv_verify_error)
        val btnOk     = dialogView.findViewById<MaterialButton>(R.id.btn_verify_confirm)

        val dialog = AlertDialog.Builder(requireContext(), R.style.Theme_PokéWallet_Dialog)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        btnOk.setOnClickListener {
            val w1      = etWord1.text?.toString()?.trim()?.lowercase() ?: ""
            val w2      = etWord2.text?.toString()?.trim()?.lowercase() ?: ""
            val pokemon = etPokemon.text?.toString()?.trim() ?: ""

            val correct1   = w1 == words[idx1].lowercase()
            val correct2   = w2 == words[idx2].lowercase()
            val correctPkm = pokemon.equals(passphrase, ignoreCase = true)

            when {
                !correct1 -> {
                    tvError.text       = "❌ A palavra #${idx1 + 1} está incorreta. Confira suas anotações."
                    tvError.visibility = View.VISIBLE
                }
                !correct2 -> {
                    tvError.text       = "❌ A palavra #${idx2 + 1} está incorreta. Confira suas anotações."
                    tvError.visibility = View.VISIBLE
                }
                !correctPkm -> {
                    tvError.text       = "❌ Pokémon incorreto. Verifique a passphrase anotada."
                    tvError.visibility = View.VISIBLE
                }
                else -> {
                    dialog.dismiss()
                    viewModel.onMnemonicConfirmed()
                }
            }
        }

        dialog.show()
    }

    private fun populateMnemonicGrid(dialogView: View, mnemonic: String) {
        val words    = mnemonic.trim().split(" ")
        val leftCol  = dialogView.findViewById<LinearLayout>(R.id.col_mnemonic_left)
        val rightCol = dialogView.findViewById<LinearLayout>(R.id.col_mnemonic_right)
        val yellow   = ContextCompat.getColor(requireContext(), R.color.accent_yellow)
        val dp4      = (4 * resources.displayMetrics.density).toInt()

        words.forEachIndexed { i, word ->
            val tv = TextView(requireContext()).apply {
                text      = "%2d. %s".format(i + 1, word)
                textSize  = 13f
                typeface  = Typeface.MONOSPACE
                isSingleLine = true
                setTextColor(yellow)
                setPadding(0, dp4, 0, dp4)
            }
            if (i < 12) leftCol.addView(tv) else rightCol.addView(tv)
        }
    }
}
