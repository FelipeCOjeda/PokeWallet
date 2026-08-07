package com.pokewallet.android

import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.TextInputEditText
import com.pokewallet.R
import com.pokewallet.crypto.Network
import com.pokewallet.crypto.PassphraseMode
import com.pokewallet.crypto.SpendType
import com.pokewallet.crypto.WalletRestore
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Diálogos de criar/restaurar carteira — extraídos de SetupFragment pra
 * serem reusados também por WalletFragment (criar/restaurar uma carteira
 * ADICIONAL, com outra já ativa). Recebem o Fragment chamador (pra
 * inflar/mostrar o diálogo) em vez de serem métodos de uma classe
 * específica.
 *
 * O resto do fluxo depois da criação (mostrar o mnemonic, quiz de
 * verificação) sempre acontece em SetupFragment — assim que
 * WalletViewModel.walletState vira Created, MainActivity navega pra lá
 * sozinho (mesmo se foi WalletFragment que iniciou a criação), então não
 * precisa duplicar essa parte aqui.
 */
object WalletCreationFlow {

    fun showPassphraseChoiceDialog(fragment: Fragment, viewModel: WalletViewModel) {
        val context = fragment.requireContext()
        val dialogView    = fragment.layoutInflater.inflate(R.layout.dialog_passphrase_choice, null)
        val chipWordCount = dialogView.findViewById<ChipGroup>(R.id.chip_group_word_count)
        val chipWords12   = dialogView.findViewById<View>(R.id.chip_words_12)
        val chipAddrType  = dialogView.findViewById<ChipGroup>(R.id.chip_group_address_type)
        val chipTaproot   = dialogView.findViewById<View>(R.id.chip_address_taproot)
        val radioGroup    = dialogView.findViewById<RadioGroup>(R.id.rg_passphrase_mode)
        val rbNone        = dialogView.findViewById<RadioButton>(R.id.rb_passphrase_none)
        val rbCustom      = dialogView.findViewById<RadioButton>(R.id.rb_passphrase_custom)
        val groupCustom   = dialogView.findViewById<View>(R.id.group_passphrase_custom)
        val etCustom      = dialogView.findViewById<TextInputEditText>(R.id.et_passphrase_custom)
        val tvCount       = dialogView.findViewById<TextView>(R.id.tv_passphrase_custom_count)
        val etWalletName  = dialogView.findViewById<TextInputEditText>(R.id.et_wallet_name)
        val tvError       = dialogView.findViewById<TextView>(R.id.tv_passphrase_choice_error)
        val btnCancel     = dialogView.findViewById<MaterialButton>(R.id.btn_cancel_passphrase_choice)
        val btnConfirm    = dialogView.findViewById<MaterialButton>(R.id.btn_confirm_passphrase_choice)

        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            groupCustom.visibility = if (checkedId == R.id.rb_passphrase_custom)
                View.VISIBLE else View.GONE
            tvError.visibility = View.GONE
        }

        etCustom.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val len = s?.length ?: 0
                tvCount.text = "$len caracteres"
                tvCount.setTextColor(ContextCompat.getColor(context,
                    if (len > 20) R.color.error_red else R.color.gb_border_soft))
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        val dialog = AlertDialog.Builder(context, R.style.Theme_PokéWallet_Dialog)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnConfirm.setOnClickListener {
            val mode = when {
                rbNone.isChecked -> PassphraseMode.None
                rbCustom.isChecked -> {
                    val value = etCustom.text?.toString()?.trim() ?: ""
                    if (value.isEmpty()) {
                        tvError.text       = "Digite uma passphrase ou escolha outra opção."
                        tvError.visibility = View.VISIBLE
                        return@setOnClickListener
                    }
                    PassphraseMode.Custom(value)
                }
                else -> PassphraseMode.Pokemon
            }
            val wordCount   = if (chipWordCount.checkedChipId == chipWords12.id) 12 else 24
            val spendType   = if (chipAddrType.checkedChipId == chipTaproot.id)
                SpendType.BIP86 else SpendType.BIP84
            val customName  = etWalletName.text?.toString()?.trim()

            dialog.dismiss()
            viewModel.createWallet(Network.MAINNET, mode, wordCount, spendType, customName)
        }

        dialog.show()
        dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
    }

    /** [onScanRequested] dispara o scanner de QR da fragment CHAMADORA
     *  (SetupFragment na tela inicial, WalletFragment em "Nova carteira")
     *  — o launcher de QR precisa ser registrado no onCreate/init da
     *  fragment (regra do Android), não dá pra criar um aqui na hora. */
    fun showRestoreDialog(
        fragment: Fragment,
        viewModel: WalletViewModel,
        onScanRequested: (prompt: String, onResult: (String) -> Unit) -> Unit
    ) {
        val context = fragment.requireContext()
        val dialogView      = fragment.layoutInflater.inflate(R.layout.dialog_restore, null)
        val etMnemonic      = dialogView.findViewById<TextInputEditText>(R.id.et_mnemonic)
        val btnScanRestore  = dialogView.findViewById<MaterialButton>(R.id.btn_scan_restore)
        val tvWordCount     = dialogView.findViewById<TextView>(R.id.tv_word_count)
        val etPassphrase    = dialogView.findViewById<TextInputEditText>(R.id.et_passphrase_restore)
        val etWalletName    = dialogView.findViewById<TextInputEditText>(R.id.et_wallet_name_restore)
        val chipGroup       = dialogView.findViewById<ChipGroup>(R.id.chip_group_network)
        val chipGroupAddr   = dialogView.findViewById<ChipGroup>(R.id.chip_group_address_type_restore)
        val progressRestore = dialogView.findViewById<ProgressBar>(R.id.progress_restore)
        val tvError         = dialogView.findViewById<TextView>(R.id.tv_restore_error)
        val btnCancel       = dialogView.findViewById<MaterialButton>(R.id.btn_cancel_restore)
        val btnConfirm      = dialogView.findViewById<MaterialButton>(R.id.btn_confirm_restore)

        var selectedNetwork = Network.MAINNET
        var selectedSpendType = SpendType.BIP84

        chipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            selectedNetwork = if (checkedIds.firstOrNull() == R.id.chip_testnet)
                Network.TESTNET else Network.MAINNET
        }

        chipGroupAddr.setOnCheckedStateChangeListener { _, checkedIds ->
            selectedSpendType = if (checkedIds.firstOrNull() == R.id.chip_restore_taproot)
                SpendType.BIP86 else SpendType.BIP84
        }

        etMnemonic.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val raw = s?.toString()?.trim() ?: ""
                if (looksLikeAccountOrigin(raw)) {
                    // Xpub colada — não é uma "contagem de palavras" de verdade,
                    // só sinaliza que o app reconheceu o formato.
                    tvWordCount.text = "chave pública reconhecida — importa como watch-only"
                    tvWordCount.setTextColor(ContextCompat.getColor(context, R.color.green_status))
                    return
                }
                val words = raw.split(Regex("\\s+")).filter { it.isNotEmpty() }
                val count = words.size
                tvWordCount.text = "$count palavras"
                tvWordCount.setTextColor(ContextCompat.getColor(context,
                    if (count == 12 || count == 24) R.color.green_status else R.color.gb_border_soft))
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        btnScanRestore.setOnClickListener {
            onScanRequested("Aponte para o QR (mnemonic ou xpub)") { scanned ->
                etMnemonic.setText(scanned.trim())
            }
        }

        val dialog = AlertDialog.Builder(context, R.style.Theme_PokéWallet_Dialog)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        // Mnemonic e passphrase são digitados nesta tela — mesma proteção
        // contra print/gravação de tela das telas de criação.
        dialog.window?.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        btnCancel.setOnClickListener {
            viewModel.resetRestoreState()
            dialog.dismiss()
        }

        btnConfirm.setOnClickListener {
            val raw   = etMnemonic.text?.toString()?.trim() ?: ""
            val passphrase = etPassphrase.text?.toString()?.trim() ?: ""
            val customName = etWalletName.text?.toString()?.trim()

            if (raw.isBlank()) {
                tvError.text       = "Cole o mnemonic (12/24 palavras) ou a xpub da carteira."
                tvError.visibility = View.VISIBLE
                return@setOnClickListener
            }

            if (looksLikeAccountOrigin(raw)) {
                // Xpub/chave pública — importa como watch-only, mesmo botão
                // "Recuperar carteira" (não faz sentido pedir passphrase, não
                // há seed nesse caminho).
                tvError.visibility  = View.GONE
                progressRestore.visibility = View.VISIBLE
                btnConfirm.isEnabled = false
                btnCancel.isEnabled  = false
                viewModel.importWatchOnly(raw, selectedNetwork, selectedSpendType, customName)
                return@setOnClickListener
            }

            val words = raw.split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (words.size != 12 && words.size != 24) {
                tvError.text       = "Informe 12 ou 24 palavras (ou uma xpub) — ${words.size} palavra(s) fornecida(s)."
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

            viewModel.restoreWallet(words, passphrase, selectedNetwork, selectedSpendType, customName)
        }

        fragment.viewLifecycleOwner.lifecycleScope.launch {
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

        // Mesmo tratamento de watchOnlyImportState de showWatchOnlyImportDialog()
        // — esta tela agora também pode disparar um import por xpub (campo
        // reconhecendo o formato sozinho), então precisa lidar com os mesmos
        // estados, incluindo o conflito "já existe com chave neste aparelho".
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            viewModel.watchOnlyImportState.collectLatest { state ->
                when (state) {
                    is WatchOnlyImportState.Importing -> {
                        progressRestore.visibility = View.VISIBLE
                        btnConfirm.isEnabled       = false
                    }
                    is WatchOnlyImportState.Success -> {
                        dialog.dismiss()
                        viewModel.resetWatchOnlyImportState()
                    }
                    is WatchOnlyImportState.Error -> {
                        progressRestore.visibility = View.GONE
                        btnConfirm.isEnabled       = true
                        btnCancel.isEnabled        = true
                        tvError.text               = state.message
                        tvError.visibility         = View.VISIBLE
                        viewModel.resetWatchOnlyImportState()
                    }
                    is WatchOnlyImportState.ConflictWithKeyedWallet -> {
                        progressRestore.visibility = View.GONE
                        btnConfirm.isEnabled       = true
                        btnCancel.isEnabled        = true
                        viewModel.resetWatchOnlyImportState()
                        val fingerprint = state.fingerprint
                        AlertDialog.Builder(context, R.style.Theme_PokéWallet_Dialog)
                            .setTitle("⚠️ Essa carteira já existe com a chave")
                            .setMessage(
                                "Essa chave pública é da MESMA carteira que já está neste " +
                                "aparelho com a seed (carteira principal).\n\n" +
                                "Pra importar como watch-only, a versão com chave precisa ser " +
                                "esquecida antes (o app não guarda as duas ao mesmo tempo). " +
                                "Antes de confirmar, tenha certeza absoluta de que o mnemonic " +
                                "e a passphrase Pokémon dessa carteira estão anotados em local " +
                                "seguro — é a única forma de recuperar o controle total dela " +
                                "depois.\n\nEsquecer a carteira principal e importar como watch-only?"
                            )
                            .setPositiveButton("Esquecer e importar") { _, _ ->
                                val raw2        = etMnemonic.text?.toString()?.trim() ?: ""
                                val customName2 = etWalletName.text?.toString()?.trim()
                                progressRestore.visibility = View.VISIBLE
                                btnConfirm.isEnabled = false
                                btnCancel.isEnabled  = false
                                viewModel.importWatchOnly(
                                    raw2, selectedNetwork, selectedSpendType, customName2,
                                    forgetKeyedFingerprint = fingerprint
                                )
                            }
                            .setNegativeButton("Cancelar", null)
                            .show()
                    }
                    is WatchOnlyImportState.Idle -> {}
                }
            }
        }

        dialog.show()
        dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
    }

    /** Detecta se o texto colado em "mnemonic" é na verdade uma chave pública
     *  (xpub pura ou "[fingerprint/path]xpub") em vez de palavras — mesmos
     *  prefixos aceitos por WalletWatchOnlyImport.parse(). Heurística barata
     *  de UI: um token único (sem espaço) começando com "[" ou um prefixo de
     *  xpub — a validação de verdade (checksum, formato completo) acontece
     *  dentro do import em si. */
    private fun looksLikeAccountOrigin(raw: String): Boolean {
        if (raw.contains(Regex("\\s"))) return false
        return raw.startsWith("[") ||
            raw.startsWith("xpub") || raw.startsWith("ypub") || raw.startsWith("zpub") ||
            raw.startsWith("tpub") || raw.startsWith("upub") || raw.startsWith("vpub")
    }

    fun showWatchOnlyImportDialog(fragment: Fragment, viewModel: WalletViewModel) {
        val context = fragment.requireContext()
        val dialogView     = fragment.layoutInflater.inflate(R.layout.dialog_watchonly_import, null)
        val etOrigin        = dialogView.findViewById<TextInputEditText>(R.id.et_account_origin)
        val etWalletName    = dialogView.findViewById<TextInputEditText>(R.id.et_wallet_name_watchonly)
        val chipGroup       = dialogView.findViewById<ChipGroup>(R.id.chip_group_network_watchonly)
        val chipGroupAddr   = dialogView.findViewById<ChipGroup>(R.id.chip_group_address_type_watchonly)
        val progress        = dialogView.findViewById<ProgressBar>(R.id.progress_watchonly)
        val tvError         = dialogView.findViewById<TextView>(R.id.tv_watchonly_error)
        val btnCancel       = dialogView.findViewById<MaterialButton>(R.id.btn_cancel_watchonly)
        val btnConfirm      = dialogView.findViewById<MaterialButton>(R.id.btn_confirm_watchonly)

        var selectedNetwork = Network.MAINNET
        var selectedSpendType = SpendType.BIP84

        chipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            selectedNetwork = if (checkedIds.firstOrNull() == R.id.chip_watchonly_testnet)
                Network.TESTNET else Network.MAINNET
        }

        chipGroupAddr.setOnCheckedStateChangeListener { _, checkedIds ->
            selectedSpendType = if (checkedIds.firstOrNull() == R.id.chip_watchonly_taproot)
                SpendType.BIP86 else SpendType.BIP84
        }

        val dialog = AlertDialog.Builder(context, R.style.Theme_PokéWallet_Dialog)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        btnCancel.setOnClickListener {
            viewModel.resetWatchOnlyImportState()
            dialog.dismiss()
        }

        btnConfirm.setOnClickListener {
            val accountOrigin = etOrigin.text?.toString()?.trim() ?: ""
            val customName    = etWalletName.text?.toString()?.trim()

            if (accountOrigin.isBlank()) {
                tvError.text       = "Cole a chave pública exportada da carteira original."
                tvError.visibility = View.VISIBLE
                return@setOnClickListener
            }

            tvError.visibility = View.GONE
            progress.visibility = View.VISIBLE
            btnConfirm.isEnabled = false
            btnCancel.isEnabled  = false

            viewModel.importWatchOnly(accountOrigin, selectedNetwork, selectedSpendType, customName)
        }

        fragment.viewLifecycleOwner.lifecycleScope.launch {
            viewModel.watchOnlyImportState.collectLatest { state ->
                when (state) {
                    is WatchOnlyImportState.Importing -> {
                        progress.visibility  = View.VISIBLE
                        btnConfirm.isEnabled = false
                    }
                    is WatchOnlyImportState.Success -> {
                        dialog.dismiss()
                        viewModel.resetWatchOnlyImportState()
                    }
                    is WatchOnlyImportState.Error -> {
                        progress.visibility  = View.GONE
                        btnConfirm.isEnabled = true
                        btnCancel.isEnabled  = true
                        tvError.text         = state.message
                        tvError.visibility   = View.VISIBLE
                        viewModel.resetWatchOnlyImportState()
                    }
                    is WatchOnlyImportState.ConflictWithKeyedWallet -> {
                        progress.visibility  = View.GONE
                        btnConfirm.isEnabled = true
                        btnCancel.isEnabled  = true
                        viewModel.resetWatchOnlyImportState()
                        val fingerprint = state.fingerprint
                        AlertDialog.Builder(context, R.style.Theme_PokéWallet_Dialog)
                            .setTitle("⚠️ Essa carteira já existe com a chave")
                            .setMessage(
                                "Essa chave pública é da MESMA carteira que já está neste " +
                                "aparelho com a seed (carteira principal).\n\n" +
                                "Pra importar como watch-only, a versão com chave precisa ser " +
                                "esquecida antes (o app não guarda as duas ao mesmo tempo). " +
                                "Antes de confirmar, tenha certeza absoluta de que o mnemonic " +
                                "e a passphrase Pokémon dessa carteira estão anotados em local " +
                                "seguro — é a única forma de recuperar o controle total dela " +
                                "depois.\n\nEsquecer a carteira principal e importar como watch-only?"
                            )
                            .setPositiveButton("Esquecer e importar") { _, _ ->
                                val accountOrigin = etOrigin.text?.toString()?.trim() ?: ""
                                val customName    = etWalletName.text?.toString()?.trim()
                                progress.visibility  = View.VISIBLE
                                btnConfirm.isEnabled = false
                                btnCancel.isEnabled  = false
                                viewModel.importWatchOnly(
                                    accountOrigin, selectedNetwork, selectedSpendType, customName,
                                    forgetKeyedFingerprint = fingerprint
                                )
                            }
                            .setNegativeButton("Cancelar", null)
                            .show()
                    }
                    is WatchOnlyImportState.Idle -> {}
                }
            }
        }

        dialog.show()
        dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
    }
}
