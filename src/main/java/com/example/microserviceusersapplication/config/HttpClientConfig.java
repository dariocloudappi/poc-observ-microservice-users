package com.example.microserviceusersapplication.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * RestTemplate usado para las llamadas salientes de demostración.
 *
 * No hay nada de OpenTelemetry aquí: el agente instrumenta RestTemplate por sí
 * mismo y genera el span de cliente. Lo único que se añade es el interceptor de
 * logging, para lo que el agente no cubre (el cuerpo) y para marcar la llamada
 * con el nombre de la dependencia.
 *
 * BufferingClientHttpRequestFactory es imprescindible: envuelve la respuesta en
 * un buffer para que el cuerpo se pueda leer dos veces. Sin ella, el
 * interceptor consume el stream al registrarlo y el controlador recibe un
 * cuerpo vacío.
 */
@Configuration
public class HttpClientConfig {

    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 5000;

    @Bean
    public RestTemplate httpBinRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        factory.setReadTimeout(READ_TIMEOUT_MS);

        RestTemplate restTemplate = new RestTemplate(new BufferingClientHttpRequestFactory(factory));
        // logBodies en true: httpbin.org es un destino inocuo y ver el payload es
        // justo el objetivo del endpoint /get. Contra una dependencia que
        // devuelva datos de personas hay que dejarlo en false.
        restTemplate.getInterceptors().add(new OutboundHttpLoggingInterceptor("httpbin", true));
        return restTemplate;
    }
}
