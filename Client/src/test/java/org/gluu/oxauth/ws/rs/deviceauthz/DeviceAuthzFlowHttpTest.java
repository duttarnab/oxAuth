/*
 * oxAuth is available under the MIT License (2008). See http://opensource.org/licenses/MIT for full text.
 *
 * Copyright (c) 2014, Gluu
 */

package org.gluu.oxauth.ws.rs.deviceauthz;

import org.gluu.oxauth.BaseTest;
import org.gluu.oxauth.client.*;
import org.gluu.oxauth.model.authorize.AuthorizeErrorResponseType;
import org.gluu.oxauth.model.common.AuthenticationMethod;
import org.gluu.oxauth.model.common.GrantType;
import org.gluu.oxauth.model.crypto.signature.RSAPublicKey;
import org.gluu.oxauth.model.crypto.signature.SignatureAlgorithm;
import org.gluu.oxauth.model.exception.InvalidJwtException;
import org.gluu.oxauth.model.jws.RSASigner;
import org.gluu.oxauth.model.jwt.Jwt;
import org.gluu.oxauth.model.jwt.JwtClaimName;
import org.gluu.oxauth.model.jwt.JwtHeaderName;
import org.gluu.oxauth.model.token.TokenErrorResponseType;
import org.gluu.oxauth.model.util.StringUtils;
import org.gluu.oxauth.page.PageConfig;
import org.openqa.selenium.By;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.FluentWait;
import org.openqa.selenium.support.ui.Wait;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.testng.Assert.*;

/**
 * Test cases for device authorization page.
 */
public class DeviceAuthzFlowHttpTest extends BaseTest {

    private static final String FORM_USER_CODE_PART_1_ID = "deviceAuthzForm:userCodePart1";
    private static final String FORM_USER_CODE_PART_2_ID = "deviceAuthzForm:userCodePart2";
    private static final String FORM_CONTINUE_BUTTON_ID = "deviceAuthzForm:continueButton";

    /**
     * Device authorization complete flow.
     */
    @Parameters({"userId", "userSecret"})
    @Test
    public void deviceAuthzFlow(final String userId, final String userSecret) throws Exception {
        showTitle("deviceAuthzFlow");

        // 1. Init device authz request from WS
        RegisterResponse registerResponse = DeviceAuthzRequestRegistrationTest.registerClientForDeviceAuthz(
                AuthenticationMethod.CLIENT_SECRET_BASIC, Collections.singletonList(GrantType.DEVICE_CODE),
                null, null, registrationEndpoint);
        String clientId = registerResponse.getClientId();
        String clientSecret = registerResponse.getClientSecret();

        // 2. Device request registration
        final List<String> scopes = Arrays.asList("openid", "profile", "address", "email", "phone", "user_name");
        DeviceAuthzRequest deviceAuthzRequest = new DeviceAuthzRequest(clientId, scopes);
        deviceAuthzRequest.setAuthUsername(clientId);
        deviceAuthzRequest.setAuthPassword(clientSecret);

        DeviceAuthzClient deviceAuthzClient = new DeviceAuthzClient(deviceAuthzEndpoint);
        deviceAuthzClient.setRequest(deviceAuthzRequest);

        DeviceAuthzResponse response = deviceAuthzClient.exec();

        showClient(deviceAuthzClient);
        DeviceAuthzRequestRegistrationTest.validateSuccessfulResponse(response);

        // 3. Load device authz page, process user_code and authorization
        WebDriver currentDriver = initWebDriver(false, true);
        processDeviceAuthzPutUserCodeAndPressContinue(response.getUserCode(), currentDriver, false);
        AuthorizationResponse authorizationResponse = processAuthorization(userId, userSecret, currentDriver);

        stopWebDriver(false, currentDriver);
        assertSuccessAuthzResponse(authorizationResponse);

        // 4. Token request
        TokenResponse tokenResponse1 = processTokens(clientId, clientSecret, response.getDeviceCode());
        validateTokenSuccessfulResponse(tokenResponse1);

        String refreshToken = tokenResponse1.getRefreshToken();
        String idToken = tokenResponse1.getIdToken();

        // 5. Validate id_token
        verifyIdToken(idToken);

        // 6. Request new access token using the refresh token.
        TokenResponse tokenResponse2 = processNewTokenWithRefreshToken(StringUtils.implode(scopes, " "),
                refreshToken, clientId, clientSecret);
        validateTokenSuccessfulResponse(tokenResponse2);

        String accessToken = tokenResponse2.getAccessToken();

        // 7. Request user info
        processUserInfo(accessToken);
    }

    /**
     * Device authorization with access denied.
     */
    @Parameters({"userId", "userSecret"})
    @Test
    public void deviceAuthzFlowAccessDenied(final String userId, final String userSecret) throws Exception {
        showTitle("deviceAuthzFlowAccessDenied");

        // 1. Init device authz request from WS
        RegisterResponse registerResponse = DeviceAuthzRequestRegistrationTest.registerClientForDeviceAuthz(
                AuthenticationMethod.CLIENT_SECRET_BASIC, Collections.singletonList(GrantType.DEVICE_CODE),
                null, null, registrationEndpoint);
        String clientId = registerResponse.getClientId();
        String clientSecret = registerResponse.getClientSecret();

        // 2. Device request registration
        final List<String> scopes = Arrays.asList("openid", "profile", "address", "email", "phone", "user_name");
        DeviceAuthzRequest deviceAuthzRequest = new DeviceAuthzRequest(clientId, scopes);
        deviceAuthzRequest.setAuthUsername(clientId);
        deviceAuthzRequest.setAuthPassword(clientSecret);

        DeviceAuthzClient deviceAuthzClient = new DeviceAuthzClient(deviceAuthzEndpoint);
        deviceAuthzClient.setRequest(deviceAuthzRequest);

        DeviceAuthzResponse response = deviceAuthzClient.exec();

        showClient(deviceAuthzClient);
        DeviceAuthzRequestRegistrationTest.validateSuccessfulResponse(response);

        // 3. Load device authz page, process user_code and authorization
        WebDriver currentDriver = initWebDriver(false, true);
        AuthorizationResponse authorizationResponse = processDeviceAuthzDenyAccess(userId, userSecret,
                response.getUserCode(), currentDriver, false);

        validateErrorResponse(authorizationResponse, AuthorizeErrorResponseType.ACCESS_DENIED);

        // 4. Token request
        TokenResponse tokenResponse = processTokens(clientId, clientSecret, response.getDeviceCode());
        assertNotNull(tokenResponse.getErrorType(), "Error expected, however no error was found");
        assertNotNull(tokenResponse.getErrorDescription(), "Error description expected, however no error was found");
        assertEquals(tokenResponse.getErrorType(), TokenErrorResponseType.ACCESS_DENIED, "Unexpected error");
    }

    /**
     * Validate server denies brute forcing
     */
    @Test
    public void preventBruteForcing() throws Exception {
        showTitle("deviceAuthzFlow");

        WebDriver currentDriver = initWebDriver(false, true);
        List<WebElement> list = currentDriver.findElements(By.xpath("//*[contains(text(),'Too many failed attemps')]"));
        byte limit = 10;
        while (list.size() == 0 && limit > 0) {
            processDeviceAuthzPutUserCodeAndPressContinue("ABCD-ABCD", currentDriver, false);
            Thread.sleep(500);
            list = currentDriver.findElements(By.xpath("//*[contains(text(),'Too many failed attemps')]"));
            limit--;
        }
        stopWebDriver(false, currentDriver);
        assertTrue(list.size() > 0 && limit > 0, "Brute forcing prevention not working correctly.");
    }

    /**
     * Verifies that token endpoint should return slow down or authorization pending states when token is in process.
     */
    @Test
    public void checkSlowDownOrPendingState() throws Exception {
        showTitle("checkSlowDownOrPendingState");

        // 1. Init device authz request from WS
        RegisterResponse registerResponse = DeviceAuthzRequestRegistrationTest.registerClientForDeviceAuthz(
                AuthenticationMethod.CLIENT_SECRET_BASIC, Collections.singletonList(GrantType.DEVICE_CODE),
                null, null, registrationEndpoint);
        String clientId = registerResponse.getClientId();
        String clientSecret = registerResponse.getClientSecret();

        // 2. Device request registration
        final List<String> scopes = Arrays.asList("openid", "profile", "address", "email", "phone", "user_name");
        DeviceAuthzRequest deviceAuthzRequest = new DeviceAuthzRequest(clientId, scopes);
        deviceAuthzRequest.setAuthUsername(clientId);
        deviceAuthzRequest.setAuthPassword(clientSecret);

        DeviceAuthzClient deviceAuthzClient = new DeviceAuthzClient(deviceAuthzEndpoint);
        deviceAuthzClient.setRequest(deviceAuthzRequest);

        DeviceAuthzResponse response = deviceAuthzClient.exec();

        showClient(deviceAuthzClient);
        DeviceAuthzRequestRegistrationTest.validateSuccessfulResponse(response);

        byte count = 3;
        while (count > 0) {
            TokenResponse tokenResponse = processTokens(clientId, clientSecret, response.getDeviceCode());
            assertNotNull(tokenResponse.getErrorType(), "Error expected, however no error was found");
            assertNotNull(tokenResponse.getErrorDescription(), "Error description expected, however no error was found");
            assertTrue(tokenResponse.getErrorType() == TokenErrorResponseType.AUTHORIZATION_PENDING
                    || tokenResponse.getErrorType() == TokenErrorResponseType.SLOW_DOWN, "Unexpected error");
            Thread.sleep(200);
            count--;
        }
    }

    /**
     * Attempts to get token with a wrong device_code, after that it attempts to get token twice,
     * second one should be rejected.
     */
    @Parameters({"userId", "userSecret"})
    @Test
    public void attemptDifferentFailedValuesToTokenEndpoint(final String userId, final String userSecret) throws Exception {
        showTitle("deviceAuthzFlow");

        // 1. Init device authz request from WS
        RegisterResponse registerResponse = DeviceAuthzRequestRegistrationTest.registerClientForDeviceAuthz(
                AuthenticationMethod.CLIENT_SECRET_BASIC, Collections.singletonList(GrantType.DEVICE_CODE),
                null, null, registrationEndpoint);
        String clientId = registerResponse.getClientId();
        String clientSecret = registerResponse.getClientSecret();

        // 2. Device request registration
        final List<String> scopes = Arrays.asList("openid", "profile", "address", "email", "phone", "user_name");
        DeviceAuthzRequest deviceAuthzRequest = new DeviceAuthzRequest(clientId, scopes);
        deviceAuthzRequest.setAuthUsername(clientId);
        deviceAuthzRequest.setAuthPassword(clientSecret);

        DeviceAuthzClient deviceAuthzClient = new DeviceAuthzClient(deviceAuthzEndpoint);
        deviceAuthzClient.setRequest(deviceAuthzRequest);

        DeviceAuthzResponse response = deviceAuthzClient.exec();

        showClient(deviceAuthzClient);
        DeviceAuthzRequestRegistrationTest.validateSuccessfulResponse(response);

        // 3. Load device authz page, process user_code and authorization
        WebDriver currentDriver = initWebDriver(false, true);
        processDeviceAuthzPutUserCodeAndPressContinue(response.getUserCode(), currentDriver, false);
        AuthorizationResponse authorizationResponse = processAuthorization(userId, userSecret, currentDriver);

        stopWebDriver(false, currentDriver);
        assertSuccessAuthzResponse(authorizationResponse);

        // 4. Token request with a wrong device code
        String wrongDeviceCode = "WRONG" + response.getDeviceCode();
        TokenResponse tokenResponse1 = processTokens(clientId, clientSecret, wrongDeviceCode);
        assertNotNull(tokenResponse1.getErrorType(), "Error expected, however no error was found");
        assertNotNull(tokenResponse1.getErrorDescription(), "Error description expected, however no error was found");
        assertEquals(tokenResponse1.getErrorType(), TokenErrorResponseType.EXPIRED_TOKEN, "Unexpected error");

        // 5. Token request with a right device code value
        tokenResponse1 = processTokens(clientId, clientSecret, response.getDeviceCode());
        validateTokenSuccessfulResponse(tokenResponse1);

        // 6. Try to get token again, however this should be rejected by the server
        tokenResponse1 = processTokens(clientId, clientSecret, response.getDeviceCode());
        assertNotNull(tokenResponse1.getErrorType(), "Error expected, however no error was found");
        assertNotNull(tokenResponse1.getErrorDescription(), "Error description expected, however no error was found");
        assertEquals(tokenResponse1.getErrorType(), TokenErrorResponseType.EXPIRED_TOKEN, "Unexpected error");
    }

    /**
     * Process a complete device authorization flow using verification_uri_complete
     */
    @Parameters({"userId", "userSecret"})
    @Test
    public void deviceAuthzFlowWithCompleteVerificationUri(final String userId, final String userSecret) throws Exception {
        showTitle("deviceAuthzFlow");

        // 1. Init device authz request from WS
        RegisterResponse registerResponse = DeviceAuthzRequestRegistrationTest.registerClientForDeviceAuthz(
                AuthenticationMethod.CLIENT_SECRET_BASIC, Collections.singletonList(GrantType.DEVICE_CODE),
                null, null, registrationEndpoint);
        String clientId = registerResponse.getClientId();
        String clientSecret = registerResponse.getClientSecret();

        // 2. Device request registration
        final List<String> scopes = Arrays.asList("openid", "profile", "address", "email", "phone", "user_name");
        DeviceAuthzRequest deviceAuthzRequest = new DeviceAuthzRequest(clientId, scopes);
        deviceAuthzRequest.setAuthUsername(clientId);
        deviceAuthzRequest.setAuthPassword(clientSecret);

        DeviceAuthzClient deviceAuthzClient = new DeviceAuthzClient(deviceAuthzEndpoint);
        deviceAuthzClient.setRequest(deviceAuthzRequest);

        DeviceAuthzResponse response = deviceAuthzClient.exec();

        showClient(deviceAuthzClient);
        DeviceAuthzRequestRegistrationTest.validateSuccessfulResponse(response);

        // 3. Load device authz page, process user_code and authorization
        WebDriver currentDriver = initWebDriver(false, true);
        processDeviceAuthzPutUserCodeAndPressContinue(response.getUserCode(), currentDriver, true);
        AuthorizationResponse authorizationResponse = processAuthorization(userId, userSecret, currentDriver);

        stopWebDriver(false, currentDriver);
        assertSuccessAuthzResponse(authorizationResponse);

        // 4. Token request
        TokenResponse tokenResponse1 = processTokens(clientId, clientSecret, response.getDeviceCode());
        validateTokenSuccessfulResponse(tokenResponse1);

        String refreshToken = tokenResponse1.getRefreshToken();
        String idToken = tokenResponse1.getIdToken();

        // 5. Validate id_token
        verifyIdToken(idToken);

        // 6. Request new access token using the refresh token.
        TokenResponse tokenResponse2 = processNewTokenWithRefreshToken(StringUtils.implode(scopes, " "),
                refreshToken, clientId, clientSecret);
        validateTokenSuccessfulResponse(tokenResponse2);

        String accessToken = tokenResponse2.getAccessToken();

        // 7. Request user info
        processUserInfo(accessToken);
    }

    /**
     * Device authorization with access denied and using complete verification uri.
     */
    @Parameters({"userId", "userSecret"})
    @Test
    public void deviceAuthzFlowAccessDeniedWithCompleteVerificationUri(final String userId, final String userSecret) throws Exception {
        showTitle("deviceAuthzFlowAccessDenied");

        // 1. Init device authz request from WS
        RegisterResponse registerResponse = DeviceAuthzRequestRegistrationTest.registerClientForDeviceAuthz(
                AuthenticationMethod.CLIENT_SECRET_BASIC, Collections.singletonList(GrantType.DEVICE_CODE),
                null, null, registrationEndpoint);
        String clientId = registerResponse.getClientId();
        String clientSecret = registerResponse.getClientSecret();

        // 2. Device request registration
        final List<String> scopes = Arrays.asList("openid", "profile", "address", "email", "phone", "user_name");
        DeviceAuthzRequest deviceAuthzRequest = new DeviceAuthzRequest(clientId, scopes);
        deviceAuthzRequest.setAuthUsername(clientId);
        deviceAuthzRequest.setAuthPassword(clientSecret);

        DeviceAuthzClient deviceAuthzClient = new DeviceAuthzClient(deviceAuthzEndpoint);
        deviceAuthzClient.setRequest(deviceAuthzRequest);

        DeviceAuthzResponse response = deviceAuthzClient.exec();

        showClient(deviceAuthzClient);
        DeviceAuthzRequestRegistrationTest.validateSuccessfulResponse(response);

        // 3. Load device authz page, process user_code and authorization
        WebDriver currentDriver = initWebDriver(false, true);
        AuthorizationResponse authorizationResponse = processDeviceAuthzDenyAccess(userId, userSecret,
                response.getUserCode(), currentDriver, true);

        validateErrorResponse(authorizationResponse, AuthorizeErrorResponseType.ACCESS_DENIED);

        // 4. Token request
        TokenResponse tokenResponse = processTokens(clientId, clientSecret, response.getDeviceCode());
        assertNotNull(tokenResponse.getErrorType(), "Error expected, however no error was found");
        assertNotNull(tokenResponse.getErrorDescription(), "Error description expected, however no error was found");
        assertEquals(tokenResponse.getErrorType(), TokenErrorResponseType.ACCESS_DENIED, "Unexpected error");
    }

    private void processUserInfo(String accessToken) throws UnrecoverableKeyException, NoSuchAlgorithmException,
            KeyStoreException, KeyManagementException {
        UserInfoClient userInfoClient = new UserInfoClient(userInfoEndpoint);
        userInfoClient.setExecutor(clientExecutor(true));
        UserInfoResponse userInfoResponse = userInfoClient.execUserInfo(accessToken);

        showClient(userInfoClient);
        assertEquals(userInfoResponse.getStatus(), 200, "Unexpected response code: " + userInfoResponse.getStatus());
        assertNotNull(userInfoResponse.getClaim(JwtClaimName.SUBJECT_IDENTIFIER));
        assertNotNull(userInfoResponse.getClaim(JwtClaimName.NAME));
        assertNotNull(userInfoResponse.getClaim(JwtClaimName.BIRTHDATE));
        assertNotNull(userInfoResponse.getClaim(JwtClaimName.FAMILY_NAME));
        assertNotNull(userInfoResponse.getClaim(JwtClaimName.GENDER));
        assertNotNull(userInfoResponse.getClaim(JwtClaimName.GIVEN_NAME));
        assertNotNull(userInfoResponse.getClaim(JwtClaimName.MIDDLE_NAME));
        assertNotNull(userInfoResponse.getClaim(JwtClaimName.NICKNAME));
        assertNotNull(userInfoResponse.getClaim(JwtClaimName.PICTURE));
        assertNotNull(userInfoResponse.getClaim(JwtClaimName.PREFERRED_USERNAME));
        assertNotNull(userInfoResponse.getClaim(JwtClaimName.PROFILE));
        assertNotNull(userInfoResponse.getClaim(JwtClaimName.WEBSITE));
        assertNotNull(userInfoResponse.getClaim(JwtClaimName.EMAIL));
        assertNotNull(userInfoResponse.getClaim(JwtClaimName.EMAIL_VERIFIED));
        assertNotNull(userInfoResponse.getClaim(JwtClaimName.PHONE_NUMBER));
        assertNotNull(userInfoResponse.getClaim(JwtClaimName.PHONE_NUMBER_VERIFIED));
        assertNotNull(userInfoResponse.getClaim(JwtClaimName.ADDRESS));
        assertNotNull(userInfoResponse.getClaim(JwtClaimName.LOCALE));
        assertNotNull(userInfoResponse.getClaim(JwtClaimName.ZONEINFO));
        assertNotNull(userInfoResponse.getClaim(JwtClaimName.USER_NAME));
        assertNull(userInfoResponse.getClaim("org_name"));
        assertNull(userInfoResponse.getClaim("work_phone"));
    }

    private TokenResponse processNewTokenWithRefreshToken(String scopes, String refreshToken, String clientId,
                                                          String clientSecret) throws UnrecoverableKeyException,
            NoSuchAlgorithmException, KeyStoreException, KeyManagementException {
        TokenClient tokenClient2 = new TokenClient(tokenEndpoint);
        tokenClient2.setExecutor(clientExecutor(true));
        TokenResponse tokenResponse2 = tokenClient2.execRefreshToken(scopes, refreshToken, clientId, clientSecret);

        showClient(tokenClient2);
        assertEquals(tokenResponse2.getStatus(), 200, "Unexpected response code: " + tokenResponse2.getStatus());
        assertNotNull(tokenResponse2.getEntity(), "The entity is null");
        assertNotNull(tokenResponse2.getAccessToken(), "The access token is null");
        assertNotNull(tokenResponse2.getTokenType(), "The token type is null");
        assertNotNull(tokenResponse2.getRefreshToken(), "The refresh token is null");
        assertNotNull(tokenResponse2.getScope(), "The scope is null");

        return tokenResponse2;
    }

    private void verifyIdToken(String idToken) throws InvalidJwtException, UnrecoverableKeyException,
            NoSuchAlgorithmException, KeyStoreException, KeyManagementException {
        Jwt jwt = Jwt.parse(idToken);
        assertNotNull(jwt.getHeader().getClaimAsString(JwtHeaderName.TYPE));
        assertNotNull(jwt.getHeader().getClaimAsString(JwtHeaderName.ALGORITHM));
        assertNotNull(jwt.getClaims().getClaimAsString(JwtClaimName.ISSUER));
        assertNotNull(jwt.getClaims().getClaimAsString(JwtClaimName.AUDIENCE));
        assertNotNull(jwt.getClaims().getClaimAsString(JwtClaimName.EXPIRATION_TIME));
        assertNotNull(jwt.getClaims().getClaimAsString(JwtClaimName.ISSUED_AT));
        assertNotNull(jwt.getClaims().getClaimAsString(JwtClaimName.SUBJECT_IDENTIFIER));
        assertNotNull(jwt.getClaims().getClaimAsString(JwtClaimName.OX_OPENID_CONNECT_VERSION));

        RSAPublicKey publicKey = JwkClient.getRSAPublicKey(
                jwksUri,
                jwt.getHeader().getClaimAsString(JwtHeaderName.KEY_ID), clientExecutor(true));
        RSASigner rsaSigner = new RSASigner(SignatureAlgorithm.RS256, publicKey);

        assertTrue(rsaSigner.validate(jwt));
    }

    private TokenResponse processTokens(String clientId, String clientSecret, String deviceCode) {
        TokenRequest tokenRequest = new TokenRequest(GrantType.DEVICE_CODE);
        tokenRequest.setAuthUsername(clientId);
        tokenRequest.setAuthPassword(clientSecret);;
        tokenRequest.setAuthenticationMethod(AuthenticationMethod.CLIENT_SECRET_BASIC);
        tokenRequest.setDeviceCode(deviceCode);

        TokenClient tokenClient1 = newTokenClient(tokenRequest);
        tokenClient1.setRequest(tokenRequest);
        TokenResponse tokenResponse1 = tokenClient1.exec();

        showClient(tokenClient1);

        return tokenResponse1;
    }

    private void validateTokenSuccessfulResponse(TokenResponse tokenResponse) {
        assertEquals(tokenResponse.getStatus(), 200, "Unexpected response code: " + tokenResponse.getStatus());
        assertNotNull(tokenResponse.getEntity(), "The entity is null");
        assertNotNull(tokenResponse.getAccessToken(), "The access token is null");
        assertNotNull(tokenResponse.getExpiresIn(), "The expires in value is null");
        assertNotNull(tokenResponse.getTokenType(), "The token type is null");
        assertNotNull(tokenResponse.getRefreshToken(), "The refresh token is null");
    }

    private void assertSuccessAuthzResponse(final AuthorizationResponse authorizationResponse) {
        assertNotNull(authorizationResponse.getCode());
        assertNotNull(authorizationResponse.getState());
        assertNull(authorizationResponse.getErrorType());
    }

    private void processDeviceAuthzPutUserCodeAndPressContinue(String userCode, WebDriver currentDriver, boolean complete) {
        String deviceAuthzPageUrl = deviceAuthzEndpoint.replace("/restv1/device_authorization", "/device_authorization.htm")
                + (complete ? "?user_code=" + userCode : "");
        System.out.println("Device authz flow: page to navigate to put user_code:" + deviceAuthzPageUrl);

        navigateToAuhorizationUrl(currentDriver, deviceAuthzPageUrl);

        if (!complete) {
            String[] userCodeParts = userCode.split("-");

            WebElement userCodePart1 = currentDriver.findElement(By.id(FORM_USER_CODE_PART_1_ID));
            userCodePart1.sendKeys(userCodeParts[0]);

            WebElement userCodePart2 = currentDriver.findElement(By.id(FORM_USER_CODE_PART_2_ID));
            userCodePart2.sendKeys(userCodeParts[1]);
        }

        WebElement continueButton = currentDriver.findElement(By.id(FORM_CONTINUE_BUTTON_ID));
        continueButton.click();
    }

    private AuthorizationResponse processAuthorization(String userId, String userSecret, WebDriver currentDriver) {
        Wait<WebDriver> wait = new FluentWait<WebDriver>(driver)
                .withTimeout(Duration.ofSeconds(PageConfig.WAIT_OPERATION_TIMEOUT))
                .pollingEvery(Duration.ofMillis(500))
                .ignoring(NoSuchElementException.class);

        if (userSecret != null) {
            final String previousUrl = currentDriver.getCurrentUrl();
            WebElement loginButton = wait.until(d -> currentDriver.findElement(By.id(loginFormLoginButton)));

            if (userId != null) {
                WebElement usernameElement = currentDriver.findElement(By.id(loginFormUsername));
                usernameElement.sendKeys(userId);
            }

            WebElement passwordElement = currentDriver.findElement(By.id(loginFormPassword));
            passwordElement.sendKeys(userSecret);

            loginButton.click();

            if (ENABLE_REDIRECT_TO_LOGIN_PAGE) {
                waitForPageSwitch(currentDriver, previousUrl);
            }
        }

        acceptAuthorization(currentDriver, null);

        String deviceAuthzResponseStr = currentDriver.getCurrentUrl();

        System.out.println("Device authz redirection response url: " + deviceAuthzResponseStr);
        return new AuthorizationResponse(deviceAuthzResponseStr);
    }

    private AuthorizationResponse processDeviceAuthzDenyAccess(String userId, String userSecret, String userCode,
                                                               WebDriver currentDriver, boolean complete) {
        String deviceAuthzPageUrl = deviceAuthzEndpoint.replace("/restv1/device_authorization", "/device_authorization.htm")
                + (complete ? "?user_code=" + userCode : "");
        System.out.println("Device authz flow: page to navigate to put user_code:" + deviceAuthzPageUrl);

        navigateToAuhorizationUrl(currentDriver, deviceAuthzPageUrl);

        if (!complete) {
            final String[] userCodeParts = userCode.split("-");

            WebElement userCodePart1 = currentDriver.findElement(By.id(FORM_USER_CODE_PART_1_ID));
            userCodePart1.sendKeys(userCodeParts[0]);

            WebElement userCodePart2 = currentDriver.findElement(By.id(FORM_USER_CODE_PART_2_ID));
            userCodePart2.sendKeys(userCodeParts[1]);
        }

        WebElement continueButton = currentDriver.findElement(By.id(FORM_CONTINUE_BUTTON_ID));
        continueButton.click();

        Wait<WebDriver> wait = new FluentWait<WebDriver>(driver)
                .withTimeout(Duration.ofSeconds(PageConfig.WAIT_OPERATION_TIMEOUT))
                .pollingEvery(Duration.ofMillis(500))
                .ignoring(NoSuchElementException.class);

        if (userSecret != null) {
            final String previousUrl = currentDriver.getCurrentUrl();
            WebElement loginButton = wait.until(d -> currentDriver.findElement(By.id(loginFormLoginButton)));

            if (userId != null) {
                WebElement usernameElement = currentDriver.findElement(By.id(loginFormUsername));
                usernameElement.sendKeys(userId);
            }

            WebElement passwordElement = currentDriver.findElement(By.id(loginFormPassword));
            passwordElement.sendKeys(userSecret);

            loginButton.click();

            if (ENABLE_REDIRECT_TO_LOGIN_PAGE) {
                waitForPageSwitch(currentDriver, previousUrl);
            }
        }

        denyAuthorization(currentDriver);

        String deviceAuthzResponseStr = currentDriver.getCurrentUrl();
        stopWebDriver(false, currentDriver);

        System.out.println("Device authz redirection response url: " + deviceAuthzResponseStr);
        return new AuthorizationResponse(deviceAuthzResponseStr);
    }

    protected void denyAuthorization(WebDriver currentDriver) {
        String authorizationResponseStr = currentDriver.getCurrentUrl();

        // Check for authorization form if client has no persistent authorization
        if (!authorizationResponseStr.contains("#")) {
            Wait<WebDriver> wait = new FluentWait<WebDriver>(driver)
                    .withTimeout(Duration.ofSeconds(PageConfig.WAIT_OPERATION_TIMEOUT))
                    .pollingEvery(Duration.ofMillis(500))
                    .ignoring(NoSuchElementException.class);

            WebElement doNotAllowButton = wait.until(d -> currentDriver.findElement(By.id(authorizeFormDoNotAllowButton)));
            final String previousUrl2 = driver.getCurrentUrl();
            doNotAllowButton.click();
            waitForPageSwitch(driver, previousUrl2);
        } else {
            fail("The authorization form was expected to be shown.");
        }
    }

    protected void validateErrorResponse(AuthorizationResponse response, AuthorizeErrorResponseType errorType) {
        assertNotNull(response.getErrorType(), "Error expected, however no error was found");
        assertNotNull(response.getErrorDescription(), "Error description expected, however no error was found");
        assertEquals(response.getErrorType(), errorType, "Unexpected error");
    }

}