package com.queuelab.core.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.testcontainers.rabbitmq.RabbitMQContainer;

import com.rabbitmq.client.GetResponse;

/**
 * Levanta un contexto con {@link JobMessagingConfiguration} y un {@code RabbitAdmin}, igual que la
 * API y el worker, contra un RabbitMQ real: la topología aparece sola al abrir la primera conexión.
 */
class JobMessagingTopologyTest {

    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-management-alpine");

    static AnnotationConfigApplicationContext context;
    static CachingConnectionFactory connectionFactory;
    static RabbitAdmin admin;
    static RabbitTemplate template;

    @BeforeAll
    static void start() {
        RABBIT.start();
        connectionFactory = new CachingConnectionFactory(RABBIT.getHost(), RABBIT.getAmqpPort());
        connectionFactory.setUsername(RABBIT.getAdminUsername());
        connectionFactory.setPassword(RABBIT.getAdminPassword());

        context = new AnnotationConfigApplicationContext();
        context.registerBean("connectionFactory", CachingConnectionFactory.class, () -> connectionFactory);
        context.registerBean("amqpAdmin", RabbitAdmin.class, () -> new RabbitAdmin(connectionFactory));
        context.register(JobMessagingConfiguration.class);
        context.refresh();

        admin = context.getBean(RabbitAdmin.class);
        template = new RabbitTemplate(connectionFactory);
        // El admin declara al abrirse la primera conexión.
        connectionFactory.createConnection().close();
    }

    @AfterAll
    static void stop() {
        context.close();
        connectionFactory.destroy();
        RABBIT.stop();
    }

    @Test
    void topologyIsDeclaredOnFirstConnection() {
        assertThat(admin.getQueueProperties(JobMessagingTopology.QUEUE)).isNotNull();
        assertThat(admin.getQueueProperties(JobMessagingTopology.DEAD_LETTER_QUEUE)).isNotNull();
        assertThat(admin.getQueueInfo(JobMessagingTopology.QUEUE).getConsumerCount()).isZero();
    }

    @Test
    void declaringAgainIsIdempotent() {
        // Un segundo proceso (API tras el worker, o una reconexión) declara lo mismo sin error.
        admin.initialize();
        admin.initialize();

        assertThat(admin.getQueueProperties(JobMessagingTopology.QUEUE)).isNotNull();
    }

    @Test
    void publishedMessageIsRoutedToTheQueueAndDecodesBack() {
        UUID id = UUID.randomUUID();
        drain(JobMessagingTopology.QUEUE);

        template.send(JobMessagingTopology.EXCHANGE, JobMessagingTopology.ROUTING_KEY,
                JobMessageCodec.encode(JobMessage.forJob(id)));

        Message received = template.receive(JobMessagingTopology.QUEUE, 5_000);
        assertThat(received).isNotNull();
        assertThat(JobMessageCodec.decode(received).jobId()).isEqualTo(id);
    }

    @Test
    void rejectedMessageGoesToTheDeadLetterQueueInsteadOfBlockingTheMainOne() {
        drain(JobMessagingTopology.QUEUE);
        drain(JobMessagingTopology.DEAD_LETTER_QUEUE);
        template.send(JobMessagingTopology.EXCHANGE, JobMessagingTopology.ROUTING_KEY,
                new Message("basura".getBytes()));

        // El consumidor rechaza sin reencolar, como hará el worker con un mensaje malformado.
        template.execute(channel -> {
            GetResponse response = null;
            for (int i = 0; i < 50 && response == null; i++) {
                response = channel.basicGet(JobMessagingTopology.QUEUE, false);
                if (response == null) {
                    sleep();
                }
            }
            assertThat(response).isNotNull();
            channel.basicNack(response.getEnvelope().getDeliveryTag(), false, false);
            return null;
        });

        Message dead = template.receive(JobMessagingTopology.DEAD_LETTER_QUEUE, 5_000);
        assertThat(dead).isNotNull();
        assertThat(new String(dead.getBody())).isEqualTo("basura");
        assertThat(template.receive(JobMessagingTopology.QUEUE, 500)).isNull();
    }

    @Test
    void eventsAreRoutedByTypeAndUnknownTypesAreRefused() {
        assertThat(JobMessagingTopology.routeFor("JOB_QUEUED"))
                .isEqualTo(new JobMessagingTopology.Route(JobMessagingTopology.EXCHANGE, JobMessagingTopology.ROUTING_KEY));
        assertThat(JobMessagingTopology.routeFor("JOB_DEAD_LETTERED")).isEqualTo(new JobMessagingTopology.Route(
                JobMessagingTopology.DEAD_LETTER_EXCHANGE, JobMessagingTopology.DEAD_LETTER_ROUTING_KEY));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> JobMessagingTopology.routeFor("OTRO"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void messagePublishedToTheDeadLetterExchangeLandsInTheDeadLetterQueue() {
        drain(JobMessagingTopology.QUEUE);
        drain(JobMessagingTopology.DEAD_LETTER_QUEUE);
        var route = JobMessagingTopology.routeFor("JOB_DEAD_LETTERED");

        template.send(route.exchange(), route.routingKey(), new Message("{}".getBytes()));

        assertThat(template.receive(JobMessagingTopology.DEAD_LETTER_QUEUE, 5_000)).isNotNull();
        assertThat(template.receive(JobMessagingTopology.QUEUE, 300)).isNull();
    }

    private static void drain(String queue) {
        while (template.receive(queue) != null) {
            // vaciar
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
