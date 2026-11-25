package com.pm.apigateway.filter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

@Component
public class JwtValidationGatewayFilterFactory extends AbstractGatewayFilterFactory<Object> {

    // WebClient is a tool for making HTTP calls from Java.
    // The gateway uses it to call the auth service.
    private final WebClient webClient;

    // @Value reads http://auth-service:4005 from application.yml.
    // baseUrl(...) sets it as the starting address, so later we only write /validate.
    public JwtValidationGatewayFilterFactory(WebClient.Builder webClientBuilder,
                                             @Value("${auth.service.url}") String authServiceUrl) {
        this.webClient = webClientBuilder.baseUrl(authServiceUrl).build();
    }

    @Override
    public GatewayFilter apply(Object config) {
        // exchange = the current request and response
        // chain = the rest of the route (next step: forward to the patient service)
        return (exchange, chain) -> {

            // Read the Authorization header, e.g. "Bearer eyJhbGci..."
            String token = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);

            // No header, or it doesn't start with "Bearer ": answer 401 and stop here.
            // The request never reaches the patient service.
            if (token == null || !token.startsWith("Bearer ")) {
                exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                return exchange.getResponse().setComplete();
            }

            // Ask the auth service (it holds the secret key) if the token is valid.
            return webClient.get()
                    .uri("/validate")                                  // calls http://auth-service:4005/validate
                    .header(HttpHeaders.AUTHORIZATION, token)          // pass the user's token along
                    .retrieve()                                        // send the request, read the reply
                    .toBodilessEntity()                                // only the status code matters
                    .flatMap(response -> chain.filter(exchange))
                    // valid -> forward to patient service
//            1. Ask the auth service: is the token valid?
//            2. If yes (200)  → flatMap: send the request on to the patient service
//            3. If no  (401)  → skip flatMap, go to onErrorResume: reply 401
                    .onErrorResume(WebClientResponseException.Unauthorized.class, ex -> {
                        // auth service said 401 -> answer 401 to the client
                        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                        return exchange.getResponse().setComplete();
                    });                                                // <-- note the }); here
        };
    }
}