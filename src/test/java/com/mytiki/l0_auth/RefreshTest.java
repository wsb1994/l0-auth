/*
 * Copyright (c) TIKI Inc.
 * MIT license. See LICENSE file in root directory.
 */

package com.mytiki.l0_auth;

import com.mytiki.l0_auth.features.latest.refresh.RefreshDO;
import com.mytiki.l0_auth.features.latest.refresh.RefreshRepository;
import com.mytiki.l0_auth.features.latest.refresh.RefreshService;
import com.mytiki.l0_auth.main.App;
import com.mytiki.l0_auth.utilities.Constants;
import com.nimbusds.jose.*;
import com.nimbusds.jwt.JWTClaimsSet;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Date;
import java.text.ParseException;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {App.class}
)
@ActiveProfiles(profiles = {"ci", "local"})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class RefreshTest {

    @Autowired
    private RefreshService service;

    @Autowired
    private RefreshRepository repository;

    @Autowired
    private JWSSigner signer;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Test
    public void Test_Token_Success() throws JOSEException {
        String token = service.token(null, null);
        assertNotNull(token);

        Jwt jwt = jwtDecoder.decode(token);
        Optional<RefreshDO> found = repository.findByJti(UUID.fromString(jwt.getId()));

        assertTrue(found.isPresent());
        assertNotNull(found.get().getIssued());
        assertTrue(found.get().getExpires().isAfter(ZonedDateTime.now()));
    }

    @Test
    public void Test_Revoke_Success() throws JOSEException, ParseException {
        String token = service.token(null, null);
        service.revoke(token);

        Jwt jwt = jwtDecoder.decode(token);
        Optional<RefreshDO> found = repository.findByJti(UUID.fromString(jwt.getId()));
        assertTrue(found.isEmpty());
    }

    @Test
    public void Test_Revoke_Replay_Success() throws JOSEException, ParseException {
        String token = service.token(null, null);
        service.revoke(token);
        service.revoke(token);

        Jwt jwt = jwtDecoder.decode(token);
        Optional<RefreshDO> found = repository.findByJti(UUID.fromString(jwt.getId()));
        assertTrue(found.isEmpty());
    }

    @Test
    public void Test_Authorize_Success() throws JOSEException, ParseException {
        String token = service.token(null, null);

        OAuth2AccessTokenResponse rsp = service.authorize(token);
        assertNotNull(rsp.getAccessToken().getTokenValue());
        assertEquals(OAuth2AccessToken.TokenType.BEARER, rsp.getAccessToken().getTokenType());
        assertNotNull(rsp.getRefreshToken());
        assertNotNull(rsp.getRefreshToken().getTokenValue());
        assertNotNull(rsp.getAccessToken().getExpiresAt());
        assertTrue(rsp.getAccessToken().getExpiresAt().isAfter(Instant.now()));

        Jwt jwt = jwtDecoder.decode(token);
        Optional<RefreshDO> found = repository.findByJti(UUID.fromString(jwt.getId()));
        assertTrue(found.isEmpty());
    }

    @Test
    public void Test_Authorize_Revoked_Success() throws JOSEException {
        String token = service.token(null, null);
        service.revoke(token);

        OAuth2AuthorizationException ex = assertThrows(OAuth2AuthorizationException.class,
                () -> service.authorize(token));
        assertEquals(ex.getError().getErrorCode(), OAuth2ErrorCodes.INVALID_GRANT);
    }

    @Test
    public void Test_Authorize_Expired_Success() throws JOSEException {
        JWSObject jws = new JWSObject(
                new JWSHeader
                        .Builder(JWSAlgorithm.ES256)
                        .type(JOSEObjectType.JWT)
                        .build(),
                new Payload(
                        new JWTClaimsSet.Builder()
                                .issuer(Constants.MODULE_DOT_PATH)
                                .issueTime(Date.from(Instant.now()))
                                .expirationTime(Date.from(Instant.now().minusSeconds(100)))
                                .jwtID(UUID.randomUUID().toString())
                                .build()
                                .toJSONObject()
                ));
        jws.sign(signer);
        String jwt = jws.serialize();

        OAuth2AuthorizationException ex = assertThrows(OAuth2AuthorizationException.class,
                () -> service.authorize(jwt));
        assertEquals(ex.getError().getErrorCode(), OAuth2ErrorCodes.INVALID_GRANT);
    }

    @Test
    public void Test_Authorize_Audience_Success() throws JOSEException, ParseException {
        String audience = "storage.l0.mytiki.com";
        String token = service.token(null, List.of(audience));

        OAuth2AccessTokenResponse rsp = service.authorize(token);
        Jwt jwt = jwtDecoder.decode(rsp.getAccessToken().getTokenValue());
        assertTrue(jwt.getAudience().contains(audience));
    }

    @Test
    public void Test_Authorize_Subject_Success() throws JOSEException, ParseException {
        String subject = UUID.randomUUID().toString();
        String token = service.token(subject, null);

        OAuth2AccessTokenResponse rsp = service.authorize(token);
        Jwt jwt = jwtDecoder.decode(rsp.getAccessToken().getTokenValue());
        assertEquals(subject, jwt.getSubject());
    }
}
