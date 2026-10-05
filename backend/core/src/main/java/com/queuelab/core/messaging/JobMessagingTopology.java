package com.queuelab.core.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;

/**
 * Nombres y declaración de la topología de RabbitMQ. API y worker usan los mismos valores.
 *
 * <pre>
 * queuelab.jobs (direct) --job.queued--> queuelab.jobs.queued --(rechazado)--> queuelab.jobs.dlx
 *                                                                                  |  job.queued.dead
 *                                                                                  v
 *                                                                        queuelab.jobs.queued.dlq
 * </pre>
 */
public final class JobMessagingTopology {

    public static final String EXCHANGE = "queuelab.jobs";
    public static final String QUEUE = "queuelab.jobs.queued";
    public static final String ROUTING_KEY = "job.queued";

    public static final String DEAD_LETTER_EXCHANGE = "queuelab.jobs.dlx";
    public static final String DEAD_LETTER_QUEUE = "queuelab.jobs.queued.dlq";
    public static final String DEAD_LETTER_ROUTING_KEY = "job.queued.dead";

    private JobMessagingTopology() {
    }

    /**
     * Todo es durable y la declaración es idempotente: volver a declararlo con los mismos
     * argumentos no hace nada. Un mensaje rechazado sin reencolar (p. ej. uno malformado) pasa a la
     * cola de mensajes muertos en lugar de bloquear la principal.
     */
    public static Declarables declarables() {
        DirectExchange exchange = ExchangeBuilder.directExchange(EXCHANGE).durable(true).build();
        Queue queue = QueueBuilder.durable(QUEUE)
                .deadLetterExchange(DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(DEAD_LETTER_ROUTING_KEY)
                .build();
        Binding binding = BindingBuilder.bind(queue).to(exchange).with(ROUTING_KEY);

        DirectExchange deadExchange = ExchangeBuilder.directExchange(DEAD_LETTER_EXCHANGE).durable(true).build();
        Queue deadQueue = QueueBuilder.durable(DEAD_LETTER_QUEUE).build();
        Binding deadBinding = BindingBuilder.bind(deadQueue).to(deadExchange).with(DEAD_LETTER_ROUTING_KEY);

        return new Declarables(exchange, queue, binding, deadExchange, deadQueue, deadBinding);
    }
}
