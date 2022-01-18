/*
 * Copyright 2021 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.fhir.proxy;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.api.RequestTypeEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.RestfulServer;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Sets;
import java.io.IOException;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.http.HttpResponse;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.ResourceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This access-checker uses the `patient_list` ID in the access token to fetch the "List" of patient
 * IDs that the given user has access to.
 */
public class ListAccessChecker implements AccessChecker {

  private static final Logger logger = LoggerFactory.getLogger(ListAccessChecker.class);
  private final FhirContext fhirContext;
  private final HttpFhirClient httpFhirClient;
  private final String patientListId;
  private final PatientFinder patientFinder;

  private ListAccessChecker(
      HttpFhirClient httpFhirClient,
      String patientListId,
      FhirContext fhirContext,
      PatientFinder patientFinder) {
    this.fhirContext = fhirContext;
    this.httpFhirClient = httpFhirClient;
    this.patientListId = patientListId;
    this.patientFinder = patientFinder;
  }

  // Note this returns true iff at least one of the patient IDs is found in the associated list.
  // The rationale is that a user should have access to a resource iff they are authorized to access
  // at least one of the patients referenced in that resource. This is a subjective decision, so we
  // may want to revisit it in the future.
  private boolean serverListIncludesAnyPatient(Set<String> patientIds) {
    if (patientIds == null) {
      return false;
    }
    // TODO consider using the HAPI FHIR client instead (b/211231483).
    String patientParam =
        patientIds.stream()
            .filter(Objects::nonNull)
            .map(p -> "Patient/" + p)
            .collect(Collectors.joining(","));
    if (patientParam.isEmpty()) {
      return false;
    }
    // We cannot use `_summary` parameter because it is not implemented on GCP yet; so to prevent a
    // potentially huge list to be fetched each time, we add `_elements=id`.
    String searchQuery =
        String.format("/List?_id=%s&item=%s&_elements=id", this.patientListId, patientParam);
    logger.debug("Search query for patient access authorization check is: {}", searchQuery);
    try {
      HttpResponse httpResponse = httpFhirClient.getResource(searchQuery);
      HttpUtil.validateResponseEntityOrFail(httpResponse, searchQuery);
      Bundle bundle = FhirUtil.parseResponseToBundle(fhirContext, httpResponse);
      // We expect exactly one result which is `patientListId`.
      return bundle.getTotal() == 1;
    } catch (IOException e) {
      logger.error("Exception while accessing " + searchQuery, e);
    }
    return false;
  }

  private boolean patientExists(String patientId) throws IOException {
    // TODO consider using the HAPI FHIR client instead (b/211231483).
    String searchQuery = String.format("/Patient?_id=%s&_elements=id", patientId);
    HttpResponse response = httpFhirClient.getResource(searchQuery);
    Bundle bundle = FhirUtil.parseResponseToBundle(fhirContext, response);
    if (bundle.getTotal() > 1) {
      logger.error(
          String.format(
              "%s patients with the same ID %s returned from the FHIR store.",
              bundle.getTotal(), patientId));
    }
    return (bundle.getTotal() > 0);
  }

  /**
   * Inspects the given request to make sure that it is for a FHIR resource of a patient that the
   * current user has access too; i.e., the patient is in the patient-list associated to the user.
   *
   * @param requestDetails the original request sent to the proxy.
   * @return true iff patient is in the patient-list associated to the current user.
   */
  @Override
  public AccessDecision checkAccess(RequestDetails requestDetails) {
    if (requestDetails.getRequestType() == RequestTypeEnum.GET) {
      // There should be a patient id in search params; the param name is based on the resource.
      if (FhirUtil.isSameResourceType(requestDetails.getResourceName(), ResourceType.List)) {
        if (patientListId.equals(FhirUtil.getIdOrNull(requestDetails))) {
          return NoOpAccessDecision.accessGranted();
        }
        return NoOpAccessDecision.accessDenied();
      }
      String patientId = patientFinder.findPatientId(requestDetails);
      return new NoOpAccessDecision(serverListIncludesAnyPatient(Sets.newHashSet(patientId)));
    }
    // We have decided to let clients add new patients while understanding its security risks.
    if (requestDetails.getRequestType() == RequestTypeEnum.POST
        && FhirUtil.isSameResourceType(requestDetails.getResourceName(), ResourceType.Patient)) {
      return new AccessGrantedAndUpdateList(patientListId, httpFhirClient, fhirContext, null);
    }
    if (requestDetails.getRequestType() == RequestTypeEnum.PUT
        && FhirUtil.isSameResourceType(requestDetails.getResourceName(), ResourceType.Patient)) {
      String patientId = FhirUtil.getIdOrNull(requestDetails);
      if (patientId == null) {
        // This is an invalid PUT request; note we are not supporting "conditional updates".
        logger.error("The provided Patient resource has no ID; denying access!");
        return NoOpAccessDecision.accessDenied();
      }
      try {
        if (patientExists(patientId)) {
          logger.info("Updating existing patient {}, so no need to update access list.", patientId);
          return new NoOpAccessDecision(serverListIncludesAnyPatient(Sets.newHashSet(patientId)));
        }
      } catch (IOException e) {
        logger.error("Exception while checking patient existence; denying access! ", e);
        return NoOpAccessDecision.accessDenied();
      }
      return new AccessGrantedAndUpdateList(patientListId, httpFhirClient, fhirContext, patientId);
    }
    // Creating/updating a non-Patient resource
    if (requestDetails.getRequestType() == RequestTypeEnum.PUT
        || requestDetails.getRequestType() == RequestTypeEnum.POST) {
      Set<String> patientIds = patientFinder.findPatientsInResource(requestDetails);
      return new NoOpAccessDecision(serverListIncludesAnyPatient(patientIds));
    }
    // TODO decide what to do for other methods like PATCH and DELETE.
    return NoOpAccessDecision.accessDenied();
  }

  public static class Factory implements AccessCheckerFactory {

    @VisibleForTesting static final String PATIENT_LIST_CLAIM = "patient_list";

    private final FhirContext fhirContext;

    public Factory(RestfulServer server) {
      this.fhirContext = server.getFhirContext();
    }

    private String getListId(DecodedJWT jwt) {
      // TODO do some sanity checks on the `patientListId` (b/207737513).
      return JwtUtil.getClaimOrDie(jwt, PATIENT_LIST_CLAIM);
    }

    @Override
    public AccessChecker create(DecodedJWT jwt, HttpFhirClient httpFhirClient) {
      String patientListId = getListId(jwt);
      PatientFinder patientFinder = PatientFinder.getInstance(fhirContext);
      return new ListAccessChecker(httpFhirClient, patientListId, fhirContext, patientFinder);
    }
  }
}