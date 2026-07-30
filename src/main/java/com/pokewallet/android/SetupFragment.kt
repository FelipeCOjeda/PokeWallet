package com.pokewallet.android

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
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
import com.pokewallet.crypto.PassphraseMode
import com.pokewallet.crypto.SpendType
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
        val btnThemeLight = view.findViewById<MaterialButton>(R.id.btn_theme_light_setup)
        val btnThemeDark  = view.findViewById<MaterialButton>(R.id.btn_theme_dark_setup)

        ThemePrefs.bindToggle(requireActivity(), btnThemeLight, btnThemeDark)

        btnCreate.setOnClickListener {
            showPassphraseChoiceDialog()
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
                        showMnemonicDialog(state.mnemonic, state.passphrase, state.passphraseMode)
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

    private fun showPassphraseChoiceDialog() {
        val dialogView    = layoutInflater.inflate(R.layout.dialog_passphrase_choice, null)
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
                tvCount.setTextColor(ContextCompat.getColor(requireContext(),
                    if (len > 20) R.color.error_red else R.color.gb_border_soft))
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        val dialog = AlertDialog.Builder(requireContext(), R.style.Theme_PokéWallet_Dialog)
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
            val wordCount = if (chipWordCount.checkedChipId == chipWords12.id) 12 else 24
            val spendType = if (chipAddrType.checkedChipId == chipTaproot.id)
                SpendType.BIP86 else SpendType.BIP84

            dialog.dismiss()
            viewModel.createWallet(Network.MAINNET, mode, wordCount, spendType)
        }

        dialog.show()
        dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
    }

    private fun showRestoreDialog() {
        val dialogView      = layoutInflater.inflate(R.layout.dialog_restore, null)
        val etMnemonic      = dialogView.findViewById<TextInputEditText>(R.id.et_mnemonic)
        val tvWordCount     = dialogView.findViewById<TextView>(R.id.tv_word_count)
        val etPassphrase    = dialogView.findViewById<TextInputEditText>(R.id.et_passphrase_restore)
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
                val words = s?.toString()?.trim()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() } ?: emptyList()
                val count = words.size
                tvWordCount.text = "$count palavras"
                tvWordCount.setTextColor(ContextCompat.getColor(requireContext(),
                    if (count == 12 || count == 24) R.color.green_status else R.color.gb_border_soft))
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        val dialog = AlertDialog.Builder(requireContext(), R.style.Theme_PokéWallet_Dialog)
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
            val words = raw.split(Regex("\\s+")).filter { it.isNotEmpty() }
            val passphrase = etPassphrase.text?.toString()?.trim() ?: ""

            if (words.size != 12 && words.size != 24) {
                tvError.text       = "Informe 12 ou 24 palavras (${words.size} fornecidas)."
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

            viewModel.restoreWallet(words, passphrase, selectedNetwork, selectedSpendType)
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

    private fun showMnemonicDialog(mnemonic: String, passphrase: String, mode: PassphraseMode) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_mnemonic, null)
        populateMnemonicGrid(dialogView, mnemonic)

        val wordCount        = mnemonic.trim().split(" ").size
        val groupPassphrase  = dialogView.findViewById<View>(R.id.group_mnemonic_passphrase)
        val tvReminder       = dialogView.findViewById<TextView>(R.id.tv_mnemonic_reminder)

        when (mode) {
            is PassphraseMode.None -> {
                groupPassphrase.visibility = View.GONE
                tvReminder.text = "✍️  Anote as $wordCount palavras offline — papel e caneta.\nNUNCA tire print ou foto."
            }
            is PassphraseMode.Pokemon -> {
                dialogView.findViewById<TextView>(R.id.tv_passphrase_label).text = "🐾  POKÉMON PASSPHRASE"
                dialogView.findViewById<TextView>(R.id.tv_passphrase).text = passphrase
                tvReminder.text = "✍️  Anote offline EXATAMENTE como está escrito acima: \"$passphrase\" " +
                    "(incluindo os dois-pontos e o número — não vale só o nome do Pokémon).\n" +
                    "NUNCA tire print ou foto."
                showPokemonSprite(dialogView, passphrase)
            }
            is PassphraseMode.Custom -> {
                dialogView.findViewById<TextView>(R.id.tv_passphrase_label).text = "🔐  PASSPHRASE PERSONALIZADA"
                dialogView.findViewById<TextView>(R.id.tv_passphrase).text = passphrase
                tvReminder.text = "✍️  Anote offline — EXATAMENTE como está escrito, sem trocar maiúscula/minúscula.\nNUNCA tire print ou foto."
            }
        }

        val dialog = AlertDialog.Builder(requireContext(), R.style.Theme_PokéWallet_Dialog)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        // Bloqueia print screen, gravação de tela e miniatura no app switcher
        // enquanto o mnemonic/passphrase estiverem visíveis nesta tela.
        dialog.window?.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        dialogView.findViewById<MaterialButton>(R.id.btn_copy_mnemonic).setOnClickListener {
            copyMnemonicAndWarn(mnemonic)
        }

        dialogView.findViewById<MaterialButton>(R.id.btn_confirm_mnemonic).setOnClickListener {
            dialog.dismiss()
            showVerifyDialog(mnemonic, passphrase, mode)
        }

        dialog.show()
    }

    private fun copyMnemonicAndWarn(mnemonic: String) {
        val clip = ClipData.newPlainText("mnemonic", mnemonic)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(clip)

        AlertDialog.Builder(requireContext(), R.style.Theme_PokéWallet_Dialog)
            .setTitle("⚠️  Cuidado")
            .setMessage(
                "Nunca exponha estas palavras online. Não faça backup em aplicativos " +
                "conectados (notas na nuvem, mensagens, e-mail).\n\n" +
                "Prefira anotar no bom e velho papel e caneta, ou gravar numa placa de metal."
            )
            .setPositiveButton("Entendi") { d, _ -> d.dismiss() }
            .setCancelable(false)
            .show()
    }

    private fun showVerifyDialog(mnemonic: String, passphrase: String, mode: PassphraseMode) {
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

        val etWord1        = dialogView.findViewById<TextInputEditText>(R.id.et_word1)
        val etWord2        = dialogView.findViewById<TextInputEditText>(R.id.et_word2)
        val etPokemon      = dialogView.findViewById<TextInputEditText>(R.id.et_pokemon)
        val tvPokemonLabel = dialogView.findViewById<TextView>(R.id.tv_pokemon_label)
        val groupPassphrase = dialogView.findViewById<View>(R.id.group_verify_passphrase)
        val tvError        = dialogView.findViewById<TextView>(R.id.tv_verify_error)
        val btnOk          = dialogView.findViewById<MaterialButton>(R.id.btn_verify_confirm)

        when (mode) {
            is PassphraseMode.None ->
                groupPassphrase.visibility = View.GONE
            is PassphraseMode.Pokemon ->
                tvPokemonLabel.text = "Qual é o seu Pokémon?"
            is PassphraseMode.Custom ->
                tvPokemonLabel.text = "Digite sua passphrase (EXATAMENTE como anotou)"
        }

        val dialog = AlertDialog.Builder(requireContext(), R.style.Theme_PokéWallet_Dialog)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        // Palavras/passphrase são redigitadas nesta tela — mesma proteção contra print/gravação.
        dialog.window?.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        btnOk.setOnClickListener {
            val w1 = etWord1.text?.toString()?.trim()?.lowercase() ?: ""
            val w2 = etWord2.text?.toString()?.trim()?.lowercase() ?: ""

            val correct1 = w1 == words[idx1].lowercase()
            val correct2 = w2 == words[idx2].lowercase()
            val correctPassphrase = when (mode) {
                is PassphraseMode.None -> true
                is PassphraseMode.Pokemon ->
                    (etPokemon.text?.toString()?.trim() ?: "").equals(passphrase, ignoreCase = true)
                is PassphraseMode.Custom ->
                    (etPokemon.text?.toString()?.trim() ?: "") == passphrase
            }

            when {
                !correct1 -> {
                    tvError.text       = "❌ A palavra #${idx1 + 1} está incorreta. Confira suas anotações."
                    tvError.visibility = View.VISIBLE
                }
                !correct2 -> {
                    tvError.text       = "❌ A palavra #${idx2 + 1} está incorreta. Confira suas anotações."
                    tvError.visibility = View.VISIBLE
                }
                !correctPassphrase -> {
                    tvError.text = if (mode is PassphraseMode.Custom)
                        "❌ Passphrase incorreta. Precisa ser EXATAMENTE igual (maiúsculas/minúsculas incluídas)."
                    else
                        "❌ Pokémon incorreto. Verifique a passphrase anotada."
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

    // Passphrase Pokémon vem no formato "pokemon:{dex}:{Nome}" (ver PokemonPassphrase.kt).
    // Os 151 sprites (estilo Game Boy Red/Blue) ficam em drawable-nodpi como pkm_001..pkm_151.
    private fun showPokemonSprite(dialogView: View, passphrase: String) {
        val img = dialogView.findViewById<ImageView>(R.id.img_pokemon_sprite)
        val dex = passphrase.split(":").getOrNull(1)?.toIntOrNull()
        if (dex == null || dex !in 1..151) {
            img.visibility = View.GONE
            return
        }
        val resId = resources.getIdentifier(
            "pkm_%03d".format(dex), "drawable", requireContext().packageName
        )
        if (resId == 0) {
            img.visibility = View.GONE
            return
        }
        img.setImageResource(resId)
        img.visibility = View.VISIBLE
    }

    private fun populateMnemonicGrid(dialogView: View, mnemonic: String) {
        val words    = mnemonic.trim().split(" ")
        val leftCol  = dialogView.findViewById<LinearLayout>(R.id.col_mnemonic_left)
        val rightCol = dialogView.findViewById<LinearLayout>(R.id.col_mnemonic_right)
        val yellow   = ContextCompat.getColor(requireContext(), R.color.accent_yellow)
        val dp4      = (4 * resources.displayMetrics.density).toInt()
        val half     = (words.size + 1) / 2

        words.forEachIndexed { i, word ->
            val tv = TextView(requireContext()).apply {
                text      = "%2d. %s".format(i + 1, word)
                textSize  = 13f
                typeface  = Typeface.MONOSPACE
                isSingleLine = true
                setTextColor(yellow)
                setPadding(0, dp4, 0, dp4)
            }
            if (i < half) leftCol.addView(tv) else rightCol.addView(tv)
        }
    }
}
