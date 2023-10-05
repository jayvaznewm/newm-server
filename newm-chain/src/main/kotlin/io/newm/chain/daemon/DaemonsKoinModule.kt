package io.newm.chain.daemon

import io.newm.shared.daemon.Daemon
import org.koin.dsl.bind
import org.koin.dsl.module

val daemonsKoinModule = module {
    single {
        BlockDaemon(
            get(),
            get(),
            get(),
            get(),
        )
    } bind Daemon::class
}
