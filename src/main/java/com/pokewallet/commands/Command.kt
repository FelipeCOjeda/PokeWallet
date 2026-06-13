package com.pokewallet.commands

interface Command {
    fun run(args: List<String>)
}
