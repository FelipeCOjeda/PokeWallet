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
import com.google.android.material.textfield.TextInputEditText
import com.pokewallet.R
import com.pokewallet.crypto.PassphraseMode
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
            WalletCreationFlow.showPassphraseChoiceDialog(this, viewModel)
        }

        btnRestore.setOnClickListener { WalletCreationFlow.showRestoreDialog(this, viewModel) }

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
