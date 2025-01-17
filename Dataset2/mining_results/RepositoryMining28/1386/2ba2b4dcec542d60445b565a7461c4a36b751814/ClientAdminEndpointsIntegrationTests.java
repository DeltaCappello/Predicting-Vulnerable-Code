/*******************************************************************************
 *     Cloud Foundry 
 *     Copyright (c) [2009-2014] Pivotal Software, Inc. All Rights Reserved.
 *
 *     This product is licensed to you under the Apache License, Version 2.0 (the "License").
 *     You may not use this product except in compliance with the License.
 *
 *     This product includes a number of subcomponents with
 *     separate copyright notices and license terms. Your use of these
 *     subcomponents is subject to the terms and conditions of the
 *     subcomponent's license, as noted in the LICENSE file.
 *******************************************************************************/
package org.cloudfoundry.identity.uaa.integration;

import org.cloudfoundry.identity.uaa.error.UaaException;
import org.cloudfoundry.identity.uaa.oauth.InvalidClientDetailsException;
import org.cloudfoundry.identity.uaa.oauth.SecretChangeRequest;
import org.cloudfoundry.identity.uaa.oauth.approval.Approval;
import org.cloudfoundry.identity.uaa.oauth.client.ClientDetailsModification;
import org.cloudfoundry.identity.uaa.test.TestAccountSetup;
import org.cloudfoundry.identity.uaa.test.UaaTestAccounts;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.crypto.codec.Base64;
import org.springframework.security.oauth2.common.DefaultOAuth2AccessToken;
import org.springframework.security.oauth2.common.OAuth2AccessToken;
import org.springframework.security.oauth2.common.util.RandomValueStringGenerator;
import org.springframework.security.oauth2.provider.BaseClientDetails;
import org.springframework.security.oauth2.provider.ClientDetails;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Map;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;


/**
 * @author Dave Syer
 * @author Luke Taylor
 */
public class ClientAdminEndpointsIntegrationTests {

    @Rule
    public ServerRunning serverRunning = ServerRunning.isRunning();

    private UaaTestAccounts testAccounts = UaaTestAccounts.standard(serverRunning);

    @Rule
    public TestAccountSetup testAccountSetup = TestAccountSetup.standard(serverRunning, testAccounts);

    private OAuth2AccessToken token;
    private HttpHeaders headers;

    @Before
    public void setUp() throws Exception {
        Assume.assumeTrue(!testAccounts.isProfileActive("vcap"));
        token = getClientCredentialsAccessToken("clients.read,clients.write");
        headers = getAuthenticatedHeaders(token);
    }

    @Test
    public void testGetClient() throws Exception {
        HttpHeaders headers = getAuthenticatedHeaders(getClientCredentialsAccessToken("clients.read"));
        ResponseEntity<String> result = serverRunning.getForString("/oauth/clients/vmc", headers);
        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertTrue(result.getBody().contains("vmc"));
    }

    @Test
    public void testListClients() throws Exception {
        HttpHeaders headers = getAuthenticatedHeaders(getClientCredentialsAccessToken("clients.read"));
        ResponseEntity<String> result = serverRunning.getForString("/oauth/clients", headers);
        assertEquals(HttpStatus.OK, result.getStatusCode());
        // System.err.println(result.getBody());
        assertTrue(result.getBody().contains("\"client_id\":\"vmc\""));
        assertFalse(result.getBody().contains("secret\":"));
    }

    @Test
    public void testCreateClient() throws Exception {
        createClient("client_credentials");
    }

    @Test
    public void testCreateClients() throws Exception {
        doCreateClients();
    }

    public ClientDetailsModification[] doCreateClients() throws Exception {
        headers = getAuthenticatedHeaders(getClientCredentialsAccessToken("clients.admin,clients.read,clients.write,clients.secret"));
        headers.add("Accept", "application/json");
        String grantTypes = "client_credentials";
        RandomValueStringGenerator gen =  new RandomValueStringGenerator();
        String[] ids = new String[5];
        ClientDetailsModification[] clients = new ClientDetailsModification[ids.length];
        for (int i=0; i<ids.length; i++) {
            ids[i] = gen.generate();
            clients[i] = new ClientDetailsModification(ids[i], "", "foo,bar",grantTypes, "uaa.none");
            clients[i].setClientSecret("secret");
            clients[i].setAdditionalInformation(Collections.<String, Object> singletonMap("foo", Arrays.asList("bar")));
        }
        ResponseEntity<ClientDetailsModification[]> result =
            serverRunning.getRestTemplate().exchange(
                serverRunning.getUrl("/oauth/clients/tx"),
                HttpMethod.POST,
                new HttpEntity<ClientDetailsModification[]>(clients, headers),
                ClientDetailsModification[].class);
        assertEquals(HttpStatus.CREATED, result.getStatusCode());
        validateClients(clients, result.getBody());
        for (int i=0; i<ids.length; i++) {
            ClientDetails client = getClient(ids[i]);
            assertNotNull(client);
        }
        return result.getBody();
    }

    @Test
    public void nonImplicitGrantClientWithoutSecretIsRejected() throws Exception {
        OAuth2AccessToken token = getClientCredentialsAccessToken("clients.read,clients.write");
        HttpHeaders headers = getAuthenticatedHeaders(token);
        BaseClientDetails client = new BaseClientDetails(new RandomValueStringGenerator().generate(), "", "foo,bar",
                        "client_credentials", "uaa.none");
        ResponseEntity<UaaException> result = serverRunning.getRestTemplate().exchange(
                        serverRunning.getUrl("/oauth/clients"), HttpMethod.POST,
                        new HttpEntity<BaseClientDetails>(client, headers), UaaException.class);
        assertEquals(HttpStatus.BAD_REQUEST, result.getStatusCode());
        assertEquals("invalid_client", result.getBody().getErrorCode());
    }

    @Test
    public void nonImplicitGrantClientWithoutSecretIsRejectedTxFails() throws Exception {
        headers = getAuthenticatedHeaders(getClientCredentialsAccessToken("clients.admin,clients.read,clients.write,clients.secret"));
        headers.add("Accept", "application/json");
        String grantTypes = "client_credentials";
        RandomValueStringGenerator gen =  new RandomValueStringGenerator();
        String[] ids = new String[5];
        BaseClientDetails[] clients = new BaseClientDetails[ids.length];
        for (int i=0; i<ids.length; i++) {
            ids[i] = gen.generate();
            clients[i] = new BaseClientDetails(ids[i], "", "foo,bar",grantTypes, "uaa.none");
            clients[i].setClientSecret("secret");
            clients[i].setAdditionalInformation(Collections.<String, Object> singletonMap("foo", Arrays.asList("bar")));
        }
        clients[clients.length-1].setClientSecret(null);
        ResponseEntity<UaaException> result =
                serverRunning.getRestTemplate().exchange(
                        serverRunning.getUrl("/oauth/clients/tx"),
                        HttpMethod.POST,
                        new HttpEntity<BaseClientDetails[]>(clients, headers),
                        UaaException.class);
        assertEquals(HttpStatus.BAD_REQUEST, result.getStatusCode());
        for (int i=0; i<ids.length; i++) {
            ClientDetails client = getClient(ids[i]);
            assertNull(client);
        }
    }

    @Test
    public void duplicateIdsIsRejectedTxFails() throws Exception {
        headers = getAuthenticatedHeaders(getClientCredentialsAccessToken("clients.admin,clients.read,clients.write,clients.secret"));
        headers.add("Accept", "application/json");
        String grantTypes = "client_credentials";
        RandomValueStringGenerator gen =  new RandomValueStringGenerator();
        String[] ids = new String[5];
        BaseClientDetails[] clients = new BaseClientDetails[ids.length];
        for (int i=0; i<ids.length; i++) {
            ids[i] = gen.generate();
            clients[i] = new BaseClientDetails(ids[i], "", "foo,bar",grantTypes, "uaa.none");
            clients[i].setClientSecret("secret");
            clients[i].setAdditionalInformation(Collections.<String, Object> singletonMap("foo", Arrays.asList("bar")));
        }
        clients[clients.length-1].setClientId(ids[0]);
        ResponseEntity<UaaException> result =
                serverRunning.getRestTemplate().exchange(
                        serverRunning.getUrl("/oauth/clients/tx"),
                        HttpMethod.POST,
                        new HttpEntity<BaseClientDetails[]>(clients, headers),
                        UaaException.class);
        assertEquals(HttpStatus.CONFLICT, result.getStatusCode());
        for (int i=0; i<ids.length; i++) {
            ClientDetails client = getClient(ids[i]);
            assertNull(client);
        }
    }

    @Test
    public void implicitAndAuthCodeGrantClient() throws Exception {
        BaseClientDetails client = new BaseClientDetails(new RandomValueStringGenerator().generate(), "", "foo,bar",
                        "implicit,authorization_code", "uaa.none");
        ResponseEntity<UaaException> result = serverRunning.getRestTemplate().exchange(
                        serverRunning.getUrl("/oauth/clients"), HttpMethod.POST,
                        new HttpEntity<BaseClientDetails>(client, headers), UaaException.class);
        assertEquals(HttpStatus.BAD_REQUEST, result.getStatusCode());
        assertEquals("invalid_client", result.getBody().getErrorCode());
    }

    @Test
    public void implicitGrantClientWithoutSecretIsOk() throws Exception {
        BaseClientDetails client = new BaseClientDetails(new RandomValueStringGenerator().generate(), "", "foo,bar",
                        "implicit", "uaa.none");
        ResponseEntity<Void> result = serverRunning.getRestTemplate().exchange(serverRunning.getUrl("/oauth/clients"),
                        HttpMethod.POST, new HttpEntity<BaseClientDetails>(client, headers), Void.class);

        assertEquals(HttpStatus.CREATED, result.getStatusCode());
    }

    @Test
    public void passwordGrantClientWithoutSecretIsOk() throws Exception {
        BaseClientDetails client = new BaseClientDetails(new RandomValueStringGenerator().generate(), "", "foo,bar",
                        "password", "uaa.none");
        ResponseEntity<Void> result = serverRunning.getRestTemplate().exchange(serverRunning.getUrl("/oauth/clients"),
                        HttpMethod.POST, new HttpEntity<BaseClientDetails>(client, headers), Void.class);

        assertEquals(HttpStatus.CREATED, result.getStatusCode());
    }

    @Test
    public void authzCodeGrantAutomaticallyAddsRefreshToken() throws Exception {
        BaseClientDetails client = createClient("authorization_code");

        ResponseEntity<String> result = serverRunning.getForString("/oauth/clients/" + client.getClientId(), headers);
        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertTrue(result.getBody().contains("\"authorized_grant_types\":[\"authorization_code\",\"refresh_token\"]"));
    }

    @Test
    public void passwordGrantAutomaticallyAddsRefreshToken() throws Exception {
        BaseClientDetails client = createClient("password");

        ResponseEntity<String> result = serverRunning.getForString("/oauth/clients/" + client.getClientId(), headers);
        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertTrue(result.getBody().contains("\"authorized_grant_types\":[\"password\",\"refresh_token\"]"));
    }

    @Test
    public void testUpdateClient() throws Exception {
        BaseClientDetails client = createClient("client_credentials");

        client.setResourceIds(Collections.singleton("foo"));
        client.setClientSecret(null);
        client.setAuthorities(AuthorityUtils.commaSeparatedStringToAuthorityList("some.crap"));
        client.setAccessTokenValiditySeconds(60);
        client.setRefreshTokenValiditySeconds(120);
        client.setAdditionalInformation(Collections.<String, Object> singletonMap("foo", Arrays.asList("rab")));

        ResponseEntity<Void> result = serverRunning.getRestTemplate().exchange(
                        serverRunning.getUrl("/oauth/clients/{client}"),
                        HttpMethod.PUT, new HttpEntity<BaseClientDetails>(client, headers), Void.class,
                        client.getClientId());
        assertEquals(HttpStatus.OK, result.getStatusCode());

        ResponseEntity<String> response = serverRunning.getForString("/oauth/clients/" + client.getClientId(), headers);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        String body = response.getBody();
        assertTrue(body.contains(client.getClientId()));
        assertTrue(body.contains("some.crap"));
        assertTrue(body.contains("refresh_token_validity\":120"));
        assertTrue(body.contains("access_token_validity\":60"));
        assertTrue("Wrong body: " + body, body.contains("\"foo\":[\"rab\"]"));

    }

    @Test
    public void testUpdateClients() throws Exception {
        BaseClientDetails[] clients = doCreateClients();
        headers = getAuthenticatedHeaders(getClientCredentialsAccessToken("clients.admin,clients.read,clients.write,clients.secret"));
        headers.add("Accept", "application/json");
        for (int i=0; i<clients.length; i++) {
            clients[i].setAuthorities(AuthorityUtils.commaSeparatedStringToAuthorityList("some.crap"));
            clients[i].setAccessTokenValiditySeconds(60);
            clients[i].setRefreshTokenValiditySeconds(120);
        }
        ResponseEntity<BaseClientDetails[]> result =
                serverRunning.getRestTemplate().exchange(
                        serverRunning.getUrl("/oauth/clients/tx"),
                        HttpMethod.PUT,
                        new HttpEntity<BaseClientDetails[]>(clients, headers),
                        BaseClientDetails[].class);
        assertEquals(HttpStatus.OK, result.getStatusCode());
        validateClients(clients, result.getBody());
        for (int i=0; i<clients.length; i++) {
            ClientDetails client = getClient(clients[i].getClientId());
            assertNotNull(client);
            assertEquals((Integer)120, client.getRefreshTokenValiditySeconds());
            assertEquals((Integer)60, client.getAccessTokenValiditySeconds());
        }
    }

    @Test
    public void testDeleteClients() throws Exception {
        BaseClientDetails[] clients = doCreateClients();
        headers = getAuthenticatedHeaders(getClientCredentialsAccessToken("clients.admin,clients.read,clients.write,clients.secret"));
        headers.add("Accept", "application/json");
        ResponseEntity<BaseClientDetails[]> result =
                serverRunning.getRestTemplate().exchange(
                        serverRunning.getUrl("/oauth/clients/tx/delete"),
                        HttpMethod.POST,
                        new HttpEntity<BaseClientDetails[]>(clients, headers),
                        BaseClientDetails[].class);
        assertEquals(HttpStatus.OK, result.getStatusCode());
        validateClients(clients,result.getBody());
        for (int i=0; i<clients.length; i++) {
            ClientDetails client = getClient(clients[i].getClientId());
            assertNull(client);
        }
    }

    @Test
    public void testDeleteClientsMissingId() throws Exception {
        BaseClientDetails[] clients = doCreateClients();
        headers = getAuthenticatedHeaders(getClientCredentialsAccessToken("clients.admin,clients.read,clients.write,clients.secret"));
        headers.add("Accept", "application/json");
        String oldId = clients[clients.length-1].getClientId();
        clients[clients.length-1].setClientId("unknown.id");
        ResponseEntity<BaseClientDetails[]> result =
                serverRunning.getRestTemplate().exchange(
                        serverRunning.getUrl("/oauth/clients/tx/delete"),
                        HttpMethod.POST,
                        new HttpEntity<BaseClientDetails[]>(clients, headers),
                        BaseClientDetails[].class);
        assertEquals(HttpStatus.NOT_FOUND, result.getStatusCode());
        clients[clients.length-1].setClientId(oldId);
        for (int i=0; i<clients.length; i++) {
            ClientDetails client = getClient(clients[i].getClientId());
            assertNotNull(client);
        }
    }

    @Test
    public void testChangeSecret() throws Exception {
        headers = getAuthenticatedHeaders(getClientCredentialsAccessToken("clients.read,clients.write,clients.secret"));
        BaseClientDetails client = createClient("client_credentials");

        client.setResourceIds(Collections.singleton("foo"));

        SecretChangeRequest change = new SecretChangeRequest();
        change.setOldSecret(client.getClientSecret());
        change.setSecret("newsecret");
        ResponseEntity<Void> result = serverRunning.getRestTemplate().exchange(
                        serverRunning.getUrl("/oauth/clients/{client}/secret"),
                        HttpMethod.PUT, new HttpEntity<SecretChangeRequest>(change, headers), Void.class,
                        client.getClientId());
        assertEquals(HttpStatus.OK, result.getStatusCode());
    }

    @Test
    public void testDeleteClient() throws Exception {
        BaseClientDetails client = createClient("client_credentials");

        client.setResourceIds(Collections.singleton("foo"));

        ResponseEntity<Void> result = serverRunning.getRestTemplate()
                        .exchange(serverRunning.getUrl("/oauth/clients/{client}"), HttpMethod.DELETE,
                                        new HttpEntity<BaseClientDetails>(client, headers), Void.class,
                                        client.getClientId());
        assertEquals(HttpStatus.OK, result.getStatusCode());
    }

    @Test
    public void testAddUpdateAndDeleteTx() throws Exception {
        ClientDetailsModification[] clients = doCreateClients();
        for (int i=1; i<clients.length; i++) {
            clients[i] = new ClientDetailsModification(clients[i]);
            clients[i].setRefreshTokenValiditySeconds(120);
            clients[i].setAction(ClientDetailsModification.UPDATE);
            clients[i].setClientSecret("secret");
        }
        clients[0].setClientId(new RandomValueStringGenerator().generate());
        clients[0].setRefreshTokenValiditySeconds(60);
        clients[0].setAction(ClientDetailsModification.ADD);
        clients[0].setClientSecret("secret");

        clients[0].setClientId(new RandomValueStringGenerator().generate());
        clients[clients.length-1].setAction(ClientDetailsModification.DELETE);


        headers = getAuthenticatedHeaders(getClientCredentialsAccessToken("clients.admin"));
        headers.add("Accept", "application/json");
        String oldId = clients[clients.length-1].getClientId();
        ResponseEntity<BaseClientDetails[]> result =
                serverRunning.getRestTemplate().exchange(
                        serverRunning.getUrl("/oauth/clients/tx/modify"),
                        HttpMethod.POST,
                        new HttpEntity<ClientDetailsModification[]>(clients, headers),
                    BaseClientDetails[].class);
        assertEquals(HttpStatus.OK, result.getStatusCode());
        //set the deleted client ID so we can verify it is gone.
        clients[clients.length-1].setClientId(oldId);
        for (int i=0; i<clients.length; i++) {
            ClientDetails client = getClient(clients[i].getClientId());
            if (i==(clients.length-1)) {
                assertNull(client);
            } else {
                assertNotNull(client);
            }
        }
    }

    @Test
    // CFID-372
    public void testCreateExistingClientFails() throws Exception {
        BaseClientDetails client = createClient("client_credentials");

        @SuppressWarnings("rawtypes")
        ResponseEntity<Map> attempt = serverRunning.getRestTemplate().exchange(serverRunning.getUrl("/oauth/clients"),
                        HttpMethod.POST, new HttpEntity<BaseClientDetails>(client, headers), Map.class);
        assertEquals(HttpStatus.CONFLICT, attempt.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, String> map = attempt.getBody();
        assertEquals("invalid_client", map.get("error"));
    }

    @Test
    public void testClientApprovalsDeleted() throws Exception {
        //create client
        BaseClientDetails client = createClient("client_credentials,password");
        assertNotNull(getClient(client.getClientId()));
        //issue a user token for this client
        OAuth2AccessToken userToken = getUserAccessToken(client.getClientId(), "secret", testAccounts.getUserName(), testAccounts.getPassword(),"oauth.approvals");
        //make sure we don't have any approvals
        Approval[] approvals = getApprovals(userToken.getValue(),client.getClientId());
        Assert.assertEquals(0, approvals.length);
        //create three approvals
        addApprovals(userToken.getValue(), client.getClientId());
        approvals = getApprovals(userToken.getValue(),client.getClientId());
        Assert.assertEquals(3, approvals.length);
        //delete the client
        ResponseEntity<Void> result = serverRunning.getRestTemplate().exchange(serverRunning.getUrl("/oauth/clients/{client}"), HttpMethod.DELETE,
            new HttpEntity<BaseClientDetails>(client, getAuthenticatedHeaders(token)), Void.class,client.getClientId());
        assertEquals(HttpStatus.OK, result.getStatusCode());

        //create a client that can read another clients approvals
        String deletedClientId = client.getClientId();
        client = createApprovalsClient("password");
        userToken = getUserAccessToken(client.getClientId(), "secret", testAccounts.getUserName(), testAccounts.getPassword(),"oauth.approvals");
        //make sure we don't have any approvals
        approvals = getApprovals(userToken.getValue(),deletedClientId);
        Assert.assertEquals(0, approvals.length);
        assertNull(getClient(deletedClientId));
    }

    @Test
    public void testClientTxApprovalsDeleted() throws Exception {
        //create client
        BaseClientDetails client = createClient("client_credentials,password");
        assertNotNull(getClient(client.getClientId()));
        //issue a user token for this client
        OAuth2AccessToken userToken = getUserAccessToken(client.getClientId(), "secret", testAccounts.getUserName(), testAccounts.getPassword(),"oauth.approvals");
        //make sure we don't have any approvals
        Approval[] approvals = getApprovals(userToken.getValue(),client.getClientId());
        Assert.assertEquals(0, approvals.length);
        //create three approvals
        addApprovals(userToken.getValue(), client.getClientId());
        approvals = getApprovals(userToken.getValue(),client.getClientId());
        Assert.assertEquals(3, approvals.length);
        //delete the client
        ResponseEntity<Void> result = serverRunning.getRestTemplate().exchange(serverRunning.getUrl("/oauth/clients/tx/delete"), HttpMethod.POST,
            new HttpEntity<BaseClientDetails[]>(new BaseClientDetails[] {client}, getAuthenticatedHeaders(getClientCredentialsAccessToken("clients.admin"))), Void.class);
        assertEquals(HttpStatus.OK, result.getStatusCode());
        //create a client that can read another clients approvals
        String deletedClientId = client.getClientId();
        client = createApprovalsClient("password");
        userToken = getUserAccessToken(client.getClientId(), "secret", testAccounts.getUserName(), testAccounts.getPassword(),"oauth.approvals");
        //make sure we don't have any approvals
        approvals = getApprovals(userToken.getValue(),deletedClientId);
        Assert.assertEquals(0, approvals.length);
        assertNull(getClient(deletedClientId));
    }

    @Test
    public void testClientTxModifyApprovalsDeleted() throws Exception {
        //create client
        ClientDetailsModification client = createClient("client_credentials,password");
        assertNotNull(getClient(client.getClientId()));
        //issue a user token for this client
        OAuth2AccessToken userToken = getUserAccessToken(client.getClientId(), "secret", testAccounts.getUserName(), testAccounts.getPassword(),"oauth.approvals");
        //make sure we don't have any approvals
        Approval[] approvals = getApprovals(userToken.getValue(),client.getClientId());
        Assert.assertEquals(0, approvals.length);
        //create three approvals
        addApprovals(userToken.getValue(), client.getClientId());
        approvals = getApprovals(userToken.getValue(),client.getClientId());
        Assert.assertEquals(3, approvals.length);
        //delete the client
        client.setAction(ClientDetailsModification.DELETE);
        ResponseEntity<Void> result = serverRunning.getRestTemplate().exchange(serverRunning.getUrl("/oauth/clients/tx/modify"), HttpMethod.POST,
            new HttpEntity<BaseClientDetails[]>(new BaseClientDetails[] {client}, getAuthenticatedHeaders(getClientCredentialsAccessToken("clients.admin"))), Void.class);
        assertEquals(HttpStatus.OK, result.getStatusCode());
        //create a client that can read another clients approvals
        String deletedClientId = client.getClientId();
        client = createApprovalsClient("password");
        userToken = getUserAccessToken(client.getClientId(), "secret", testAccounts.getUserName(), testAccounts.getPassword(),"oauth.approvals");
        //make sure we don't have any approvals
        approvals = getApprovals(userToken.getValue(),deletedClientId);
        Assert.assertEquals(0, approvals.length);
        assertNull(getClient(deletedClientId));
    }

    private Approval[] getApprovals(String token, String clientId) throws Exception {
        String filter = "client_id eq '"+clientId+"'";
        HttpHeaders headers = getAuthenticatedHeaders(token);

        ResponseEntity<Approval[]> approvals =
            serverRunning.getRestTemplate().exchange(serverRunning.getUrl("/approvals?filter={filter}"),
                HttpMethod.GET,
                new HttpEntity<Object>(headers),
                Approval[].class,
                filter);
        assertEquals(HttpStatus.OK, approvals.getStatusCode());
        return approvals.getBody();
    }


    private Approval[] addApprovals(String token, String clientId) throws Exception {
        Date oneMinuteAgo = new Date(System.currentTimeMillis() - 60000);
        Date expiresAt = new Date(System.currentTimeMillis() + 60000);
        Approval[] approvals = new Approval[] {
            new Approval(null, clientId, "cloud_controller.read", expiresAt, Approval.ApprovalStatus.APPROVED,oneMinuteAgo),
            new Approval(null, clientId, "openid", expiresAt, Approval.ApprovalStatus.APPROVED,oneMinuteAgo),
            new Approval(null, clientId, "password.write", expiresAt, Approval.ApprovalStatus.APPROVED,oneMinuteAgo)
        };

        HttpHeaders headers = getAuthenticatedHeaders(token);
        HttpEntity<Approval[]> entity = new HttpEntity<Approval[]>(approvals, headers);
        ResponseEntity<Approval[]> response = serverRunning.getRestTemplate().exchange(
            serverRunning.getUrl("/approvals/{clientId}"),
            HttpMethod.PUT,
            entity,
            Approval[].class,
            clientId);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        return response.getBody();
    }

    private ClientDetailsModification createClient(String grantTypes) throws Exception {
        ClientDetailsModification client = new ClientDetailsModification(new RandomValueStringGenerator().generate(), "", "oauth.approvals,foo,bar",grantTypes, "uaa.none");
        client.setClientSecret("secret");
        client.setAdditionalInformation(Collections.<String, Object>singletonMap("foo", Arrays.asList("bar")));
        ResponseEntity<Void> result = serverRunning.getRestTemplate().exchange(serverRunning.getUrl("/oauth/clients"),
                        HttpMethod.POST, new HttpEntity<BaseClientDetails>(client, headers), Void.class);
        assertEquals(HttpStatus.CREATED, result.getStatusCode());
        return client;
    }

    private ClientDetailsModification createApprovalsClient(String grantTypes) throws Exception {
        ClientDetailsModification client = new ClientDetailsModification(new RandomValueStringGenerator().generate(), "", "oauth.login,oauth.approvals,foo,bar",grantTypes, "uaa.none");
        client.setClientSecret("secret");
        client.setAdditionalInformation(Collections.<String, Object> singletonMap("foo", Arrays.asList("bar")));
        ResponseEntity<Void> result = serverRunning.getRestTemplate().exchange(serverRunning.getUrl("/oauth/clients"),
                HttpMethod.POST, new HttpEntity<BaseClientDetails>(client, headers), Void.class);
        assertEquals(HttpStatus.CREATED, result.getStatusCode());
        return client;
    }

    public HttpHeaders getAuthenticatedHeaders(OAuth2AccessToken token) {
        return getAuthenticatedHeaders(token.getValue());
    }

    public HttpHeaders getAuthenticatedHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(Arrays.asList(MediaType.APPLICATION_JSON));
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + token);
        return headers;
    }

    private OAuth2AccessToken getClientCredentialsAccessToken(String scope) throws Exception {

        String clientId = testAccounts.getAdminClientId();
        String clientSecret = testAccounts.getAdminClientSecret();

        MultiValueMap<String, String> formData = new LinkedMultiValueMap<String, String>();
        formData.add("grant_type", "client_credentials");
        formData.add("client_id", clientId);
        formData.add("scope", scope);
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(Arrays.asList(MediaType.APPLICATION_JSON));
        headers.set("Authorization",
                        "Basic " + new String(Base64.encode(String.format("%s:%s", clientId, clientSecret).getBytes())));

        @SuppressWarnings("rawtypes")
        ResponseEntity<Map> response = serverRunning.postForMap("/oauth/token", formData, headers);
        assertEquals(HttpStatus.OK, response.getStatusCode());

        @SuppressWarnings("unchecked")
        OAuth2AccessToken accessToken = DefaultOAuth2AccessToken.valueOf(response.getBody());
        return accessToken;

    }

    private OAuth2AccessToken getUserAccessToken(String clientId, String clientSecret, String username, String password, String scope) throws Exception {
        MultiValueMap<String, String> formData = new LinkedMultiValueMap<String, String>();
        formData.add("grant_type", "password");
        formData.add("client_id", clientId);
        formData.add("scope", scope);
        formData.add("username", username);
        formData.add("password", password);
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(Arrays.asList(MediaType.APPLICATION_JSON));
        headers.set("Authorization",
            "Basic " + new String(Base64.encode(String.format("%s:%s", clientId, clientSecret).getBytes())));

        @SuppressWarnings("rawtypes")
        ResponseEntity<Map> response = serverRunning.postForMap("/oauth/token", formData, headers);
        assertEquals(HttpStatus.OK, response.getStatusCode());

        @SuppressWarnings("unchecked")
        OAuth2AccessToken accessToken = DefaultOAuth2AccessToken.valueOf(response.getBody());
        return accessToken;

    }

    public ClientDetails getClient(String id) throws Exception {
        HttpHeaders headers = getAuthenticatedHeaders(getClientCredentialsAccessToken("clients.read"));
        ResponseEntity<BaseClientDetails> result =
                serverRunning.getRestTemplate().exchange(
                        serverRunning.getUrl("/oauth/clients/"+id),
                        HttpMethod.GET,
                        new HttpEntity<Void>(null, headers),
                        BaseClientDetails.class);


        if (result.getStatusCode()==HttpStatus.NOT_FOUND) {
            return null;
        } else if (result.getStatusCode()==HttpStatus.OK) {
            return result.getBody();
        } else {
            throw new InvalidClientDetailsException("Unknown status code:"+result.getStatusCode());
        }

    }

    public boolean validateClients(BaseClientDetails[] expected, BaseClientDetails[] actual) {
        assertNotNull(expected);
        assertNotNull(actual);
        assertEquals(expected.length, actual.length);
        for (int i=0; i<expected.length; i++) {
            assertNotNull(expected[i]);
            assertNotNull(actual[i]);
            assertEquals(expected[i].getClientId(), actual[i].getClientId());
        }
        return true;
    }

    private static class ClientIdComparator implements Comparator<BaseClientDetails> {
        @Override
        public int compare(BaseClientDetails o1, BaseClientDetails o2) {
            return (o1.getClientId().compareTo(o2.getClientId()));
        }

        @Override
        public boolean equals(Object obj) {
            return obj==this;
        }
    }

}
