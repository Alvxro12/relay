package io.github.alvxro12.relay.webhook.service;

public enum WebhookIngestOutcome {
    /** Evento nuevo: persistido y encolado. */
    ACCEPTED,
    /** Ya lo habíamos recibido: no se reinserta ni se vuelve a encolar. */
    DUPLICATE,
    /** El cuerpo no es JSON o no trae id de evento: no hay nada que persistir. */
    UNPARSEABLE
}
