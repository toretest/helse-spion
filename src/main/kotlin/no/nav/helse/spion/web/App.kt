package no.nav.helse.spion.web

import com.typesafe.config.ConfigFactory
import io.ktor.config.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.util.*
import kotlinx.coroutines.runBlocking
import no.nav.helse.arbeidsgiver.kubernetes.KubernetesProbeManager
import no.nav.helse.arbeidsgiver.kubernetes.LivenessComponent
import no.nav.helse.arbeidsgiver.kubernetes.ReadynessComponent
import no.nav.helse.arbeidsgiver.bakgrunnsjobb.BakgrunnsjobbService
import no.nav.helse.spion.vedtaksmelding.VedtaksmeldingConsumer
import no.nav.helse.spion.vedtaksmelding.VedtaksmeldingProcessor
import org.koin.ktor.ext.getKoin
import org.slf4j.LoggerFactory

val mainLogger = LoggerFactory.getLogger("main")

@KtorExperimentalAPI
fun main() {
    Thread.currentThread().setUncaughtExceptionHandler { thread, err ->
        mainLogger.error("uncaught exception in thread ${thread.name}: ${err.message}", err)
    }

    embeddedServer(Netty, createApplicationEnvironment()).let { app ->
        app.start(wait = false)

        val koin = app.application.getKoin()
        val bakgrunnsjobbService = koin.get<BakgrunnsjobbService>()
        bakgrunnsjobbService.leggTilBakgrunnsjobbProsesserer(VedtaksmeldingProcessor.JOBB_TYPE, koin.get<VedtaksmeldingProcessor>())
        bakgrunnsjobbService.startAsync(true)

        runBlocking { autoDetectProbeableComponents(koin) }
        mainLogger.info("La til probeable komponenter")

        val vedtaksmeldingConsumer = koin.get<VedtaksmeldingConsumer>()
        vedtaksmeldingConsumer.startAsync(true)

        Runtime.getRuntime().addShutdownHook(Thread {
            vedtaksmeldingConsumer.stop()
            app.stop(1000, 1000)
        })
    }
}

private suspend fun autoDetectProbeableComponents(koin: org.koin.core.Koin) {
    val kubernetesProbeManager = koin.get<KubernetesProbeManager>()

    koin.getAllOfType<LivenessComponent>()
            .forEach { kubernetesProbeManager.registerLivenessComponent(it) }

    koin.getAllOfType<ReadynessComponent>()
            .forEach { kubernetesProbeManager.registerReadynessComponent(it) }
}

@KtorExperimentalAPI
fun createApplicationEnvironment() = applicationEngineEnvironment {
    config = HoconApplicationConfig(ConfigFactory.load())

    connector {
        port = 8080
    }

    module {
        spionModule(config)
    }
}

