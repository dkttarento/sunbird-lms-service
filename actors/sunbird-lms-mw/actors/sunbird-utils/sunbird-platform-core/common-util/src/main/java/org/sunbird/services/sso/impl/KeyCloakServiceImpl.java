package org.sunbird.services.sso.impl;

import static java.util.Arrays.asList;
import static org.sunbird.common.models.util.ProjectUtil.isNotNull;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.keycloak.RSATokenVerifier;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.representations.AccessToken;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.keycloak.util.JsonSerialization;
import org.sunbird.common.exception.ProjectCommonException;
import org.sunbird.common.models.util.*;
import org.sunbird.common.request.RequestContext;
import org.sunbird.common.responsecode.ResponseCode;
import org.sunbird.services.sso.SSOManager;

import javax.ws.rs.ClientErrorException;
import javax.ws.rs.core.Response;

/**
 * Single sign out service implementation with Key Cloak.
 *
 * @author Manzarul
 */
public class KeyCloakServiceImpl implements SSOManager {
  private LoggerUtil logger = new LoggerUtil(KeyCloakServiceImpl.class);
  private Keycloak keycloak = KeyCloakConnectionProvider.getConnection();

  private static PublicKey SSO_PUBLIC_KEY = null;

  public PublicKey getPublicKey() {
    if (null == SSO_PUBLIC_KEY) {
      SSO_PUBLIC_KEY = toPublicKey(System.getenv(JsonKey.SSO_PUBLIC_KEY));
    }
    return SSO_PUBLIC_KEY;
  }

  @Override
  public String verifyToken(String accessToken, RequestContext context) {
    return verifyToken(accessToken, null, context);
  }

  /**
   * This method will generate Public key form keycloak realm publickey String
   *
   * @param publicKeyString String
   * @return PublicKey
   */
  private PublicKey toPublicKey(String publicKeyString) {
    try {
      byte[] publicBytes = Base64.getDecoder().decode(publicKeyString);
      X509EncodedKeySpec keySpec = new X509EncodedKeySpec(publicBytes);
      KeyFactory keyFactory = KeyFactory.getInstance("RSA");
      return keyFactory.generatePublic(keySpec);
    } catch (Exception e) {
      return null;
    }
  }

  @Override
  public boolean updatePassword(String userId, String password, RequestContext context) {
    try {
      String fedUserId = getFederatedUserId(userId);
      UserResource ur = keycloak.realm(KeyCloakConnectionProvider.SSO_REALM).users().get(fedUserId);
      CredentialRepresentation cr = new CredentialRepresentation();
      cr.setType(CredentialRepresentation.PASSWORD);
      cr.setValue(password);
      ur.resetPassword(cr);
      return true;
    } catch (Exception e) {
      logger.error(context, "updatePassword: Exception occurred: ", e);
    }
    return false;
  }

  /**
   * Method to remove the user on basis of user id.
   *
   * @param request Map
   * @param context
   * @return boolean true if success otherwise false .
   */
  @Override
  public String removeUser(Map<String, Object> request, RequestContext context) {
    Keycloak keycloak = KeyCloakConnectionProvider.getConnection();
    String userId = (String) request.get(JsonKey.USER_ID);
    try {
      String fedUserId = getFederatedUserId(userId);
      UserResource resource =
          keycloak.realm(KeyCloakConnectionProvider.SSO_REALM).users().get(fedUserId);
      if (isNotNull(resource)) {
        resource.remove();
      }
    } catch (Exception ex) {
      logger.error(context, "Error occurred : ", ex);
      ProjectUtil.createAndThrowInvalidUserDataException();
    }
    return JsonKey.SUCCESS;
  }

  /**
   * Method to deactivate the user on basis of user id.
   *
   * @param request Map
   * @param context
   * @return boolean true if success otherwise false .
   */
  @Override
  public String deactivateUser(Map<String, Object> request, RequestContext context) {
    String userId = (String) request.get(JsonKey.USER_ID);
    makeUserActiveOrInactive(userId, false, context);
    return JsonKey.SUCCESS;
  }

  /**
   * Method to activate the user on basis of user id.
   *
   * @param request Map
   * @param context
   * @return boolean true if success otherwise false .
   */
  @Override
  public String activateUser(Map<String, Object> request, RequestContext context) {
    String userId = (String) request.get(JsonKey.USER_ID);
    makeUserActiveOrInactive(userId, true, context);
    return JsonKey.SUCCESS;
  }

  /**
   * This method will take userid and boolean status to update user status
   *
   * @param userId String
   * @param status boolean
   * @throws ProjectCommonException
   */
  private void makeUserActiveOrInactive(String userId, boolean status, RequestContext context) {
    try {
      String fedUserId = getFederatedUserId(userId);
      logger.info(context, "makeUserActiveOrInactive: fedration id formed: " + fedUserId);
      validateUserId(fedUserId);
      Keycloak keycloak = KeyCloakConnectionProvider.getConnection();
      UserResource resource =
          keycloak.realm(KeyCloakConnectionProvider.SSO_REALM).users().get(fedUserId);
      UserRepresentation ur = resource.toRepresentation();
      ur.setEnabled(status);
      if (isNotNull(resource)) {
        resource.update(ur);
      }
    } catch(ClientErrorException ce) {
      handleClientErrorException(context, ce);
    } catch (Exception e) {
      Throwable cause = e.getCause();
      if (cause instanceof ClientErrorException) {
        handleClientErrorException(context, (ClientErrorException) cause);
      } else {
        e.printStackTrace();
      }
      logger.error(
          context,
          "makeUserActiveOrInactive:error occurred while blocking or unblocking user: ",
          e);
      ProjectUtil.createAndThrowInvalidUserDataException();
    }
  }

  private void handleClientErrorException(RequestContext context, ClientErrorException e) {
    Response response = e.getResponse();
    try {
      logger.info(context, "status: " + response.getStatus());
      logger.info(context,"reason: " + response.getStatusInfo().getReasonPhrase());
      Map<String, Object> error = (new ObjectMapper()).readValue((ByteArrayInputStream) response.getEntity(),
              new TypeReference<HashMap<String, Object>>() {
              });
      logger.info(context,"error: " + error.get("error"));
      logger.info(context,"error_description: " + error.get("error_description"));
    } catch (Exception ex) {
      logger.error("Failed to handleClientErrorException. ", ex);
    }
  }

  /**
   * This method will check userId value, if value is null or empty then it will throw
   * ProjectCommonException
   *
   * @param userId String
   * @throws ProjectCommonException
   */
  private void validateUserId(String userId) {
    if (StringUtils.isBlank(userId)) {
      ProjectUtil.createAndThrowInvalidUserDataException();
    }
  }

  private String getFederatedUserId(String userId) {
    return String.join(
        ":",
        "f",
        ProjectUtil.getConfigValue(JsonKey.SUNBIRD_KEYCLOAK_USER_FEDERATION_PROVIDER_ID),
        userId);
  }

  @Override
  public void setRequiredAction(String userId, String requiredAction) {
    String fedUserId = getFederatedUserId(userId);
    UserResource resource =
        keycloak.realm(KeyCloakConnectionProvider.SSO_REALM).users().get(fedUserId);

    UserRepresentation userRepresentation = resource.toRepresentation();
    userRepresentation.setRequiredActions(asList(requiredAction));
    resource.update(userRepresentation);
  }

  @Override
  public String verifyToken(String accessToken, String url, RequestContext context) {

    try {
      PublicKey publicKey = getPublicKey();
      if (publicKey != null) {
        String ssoUrl = (url != null ? url : KeyCloakConnectionProvider.SSO_URL);
        AccessToken token =
            RSATokenVerifier.verifyToken(
                accessToken,
                publicKey,
                ssoUrl + "realms/" + KeyCloakConnectionProvider.SSO_REALM,
                true,
                true);
        logger.info(
            context,
            token.getId()
                + " "
                + token.issuedFor
                + " "
                + token.getProfile()
                + " "
                + token.getSubject()
                + " Active: "
                + token.isActive()
                + "  isExpired: "
                + token.isExpired()
                + " "
                + token.issuedNow().getExpiration());
        String tokenSubject = token.getSubject();
        if (StringUtils.isNotBlank(tokenSubject)) {
          int pos = tokenSubject.lastIndexOf(":");
          return tokenSubject.substring(pos + 1);
        }
        return token.getSubject();
      } else {
        logger.info(context, "verifyToken: SSO_PUBLIC_KEY is NULL.");
        throw new ProjectCommonException(
            ResponseCode.keyCloakDefaultError.getErrorCode(),
            ResponseCode.keyCloakDefaultError.getErrorMessage(),
            ResponseCode.keyCloakDefaultError.getResponseCode());
      }
    } catch (Exception e) {
      logger.error(context, "verifyToken: Exception occurred: ", e);
      throw new ProjectCommonException(
          ResponseCode.unAuthorized.getErrorCode(),
          ResponseCode.unAuthorized.getErrorMessage(),
          ResponseCode.UNAUTHORIZED.getResponseCode());
    }
  }
}
