
import io.prometheus.metrics.core.metrics.Counter
import io.prometheus.metrics.core.metrics.Gauge

object Metrics {
    val receivedGuildMessageEvents = Counter.builder()
        .name("received_guild_message_events")
        .help("The number of guild message events received by the bot per minute (excluding bot messages)")
        .register()

    val receivedMessageEvents = Counter.builder()
        .name("received_message_events")
        .help("Number of message events received by the bot per minute")
        .register()

    val uncachedDeletedMessages = Counter.builder()
        .name("uncached_deleted_messages")
        .help("Number of deleted messages events that aren't cached")
        .register()

    val activeCoroutines = Gauge.builder()
        .name("active_coroutines")
        .help("Number of currently active coroutines")
        .register()

}