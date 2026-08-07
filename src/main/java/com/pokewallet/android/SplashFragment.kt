package com.pokewallet.android

import android.animation.ObjectAnimator
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.pokewallet.R

/**
 * Tela de abertura própria (não um vídeo de terceiros) — sequência inspirada
 * na abertura clássica do Pokémon Yellow (Pikachu entrando andando, flash,
 * pokébola, título), mas com animações e assets próprios do app, sem usar o
 * clipe/áudio original. Some sozinha depois de [AUTO_ADVANCE_MS] ou antes se
 * o usuário tocar na tela.
 */
class SplashFragment : Fragment() {

    private var advanced = false
    private val runningAnimators = mutableListOf<ObjectAnimator>()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_splash, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val pikachu  = view.findViewById<ImageView>(R.id.img_splash_pikachu)
        val pokeball = view.findViewById<ImageView>(R.id.img_splash_pokeball)
        val title    = view.findViewById<ImageView>(R.id.img_splash_logo)
        val hint     = view.findViewById<TextView>(R.id.tv_splash_hint)
        val flash    = view.findViewById<View>(R.id.view_splash_flash)

        // Pikachu entra andando da esquerda pra o centro, com um leve "bounce"
        // vertical simulando passos — some num flash antes da pokébola/título.
        pikachu.post {
            pikachu.translationX = -pikachu.width.toFloat() - 80f
            pikachu.alpha = 1f

            val walkIn = ObjectAnimator.ofFloat(pikachu, View.TRANSLATION_X, pikachu.translationX, 0f).apply {
                duration = 1100
                interpolator = DecelerateInterpolator()
            }
            val bounce = ObjectAnimator.ofFloat(pikachu, View.TRANSLATION_Y, 0f, -14f, 0f).apply {
                duration = 260
                repeatCount = ObjectAnimator.INFINITE
                interpolator = LinearInterpolator()
            }
            runningAnimators += walkIn
            runningAnimators += bounce
            walkIn.start()
            bounce.start()
        }

        view.postDelayed({
            // Flash rápido — Pikachu "sai de cena" no clarão, igual a
            // transição clássica de intro de jogo.
            flash.animate().alpha(1f).setDuration(120).withEndAction {
                pikachu.animate().alpha(0f).setDuration(80).start()
                flash.animate().alpha(0f).setDuration(220).start()
            }.start()
        }, 1250)

        view.postDelayed({
            runningAnimators.forEach { it.cancel() }
            val spin = ObjectAnimator.ofFloat(pokeball, View.ROTATION, 0f, 360f).apply {
                duration = 1600
                repeatCount = ObjectAnimator.INFINITE
                interpolator = LinearInterpolator()
            }
            runningAnimators += spin
            spin.start()
            pokeball.scaleX = 0.4f
            pokeball.scaleY = 0.4f
            pokeball.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(450).setInterpolator(AccelerateDecelerateInterpolator()).start()
        }, 1550)

        view.postDelayed({
            title.scaleX = 0.7f
            title.scaleY = 0.7f
            title.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(600).start()
        }, 2050)

        view.postDelayed({
            hint.animate().alpha(1f).setDuration(500).start()
        }, 2900)

        view.setOnClickListener { advance() }
        view.postDelayed({ advance() }, AUTO_ADVANCE_MS)
    }

    override fun onDestroyView() {
        runningAnimators.forEach { it.cancel() }
        runningAnimators.clear()
        super.onDestroyView()
    }

    private fun advance() {
        if (advanced) return
        advanced = true
        (activity as? MainActivity)?.advancePastSplash()
    }

    companion object {
        private const val AUTO_ADVANCE_MS = 4200L
    }
}
