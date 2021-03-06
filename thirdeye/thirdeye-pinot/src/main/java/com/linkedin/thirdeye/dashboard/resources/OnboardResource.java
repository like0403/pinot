package com.linkedin.thirdeye.dashboard.resources;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linkedin.thirdeye.client.DAORegistry;
import com.linkedin.thirdeye.datalayer.bao.AnomalyFunctionManager;
import com.linkedin.thirdeye.datalayer.dto.AnomalyFunctionDTO;
import com.linkedin.thirdeye.datalayer.bao.MergedAnomalyResultManager;
import com.linkedin.thirdeye.datalayer.dto.MergedAnomalyResultDTO;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;

@Path("/onboard")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class OnboardResource {
  private final AnomalyFunctionManager anomalyFunctionDAO;
  private final MergedAnomalyResultManager mergedAnomalyResultDAO;

  private static final DAORegistry DAO_REGISTRY = DAORegistry.getInstance();
  private static final Logger LOG = LoggerFactory.getLogger(OnboardResource.class);

  public OnboardResource() {
    this.anomalyFunctionDAO = DAO_REGISTRY.getAnomalyFunctionDAO();
    this.mergedAnomalyResultDAO = DAO_REGISTRY.getMergedAnomalyResultDAO();
  }

  // endpoint clone function Ids to append a name defined in nameTags

  @GET
  @Path("function/{id}")
  public AnomalyFunctionDTO getAnomalyFunction(@PathParam("id") Long id) {
    return anomalyFunctionDAO.findById(id);
  }

  // clone functions in batch
  @POST
  @Path("function/clone")
  public List<Long> cloneFunctionsGetIds(@QueryParam("functionId") String functionIds,
      @QueryParam("nameTags") String nameTags,
      @QueryParam("cloneAnomaly")@DefaultValue("false") String cloneAnomaly)
      throws Exception {
    ArrayList<Long> cloneFunctionIds = new ArrayList<>();
    try {
      Map<Long, String> idNameTagMap = parseFunctionIdsAndNameTags(functionIds, nameTags);

      for (Map.Entry<Long, String> entry : idNameTagMap.entrySet()) {
        cloneFunctionIds.add(cloneAnomalyFunctionById(entry.getKey(), entry.getValue(), Boolean.valueOf(cloneAnomaly)));
      }
    } catch (Exception e) {
      LOG.error("Can not clone function Ids {}, with name tags {}", functionIds, nameTags);
      throw new WebApplicationException(e);
    }
    return cloneFunctionIds;
  }

  // clone function 1 by 1
  @POST
  @Path("function/{id}/clone/{tag}")
  public Long cloneFunctionsGetIds(@PathParam("id") Long id,
      @PathParam("tag") String tag,
      @QueryParam("cloneAnomaly")@DefaultValue("false") String cloneAnomaly)
      throws Exception {
    try {
      return cloneAnomalyFunctionById(id, tag, Boolean.valueOf(cloneAnomaly));
    } catch (Exception e) {
      LOG.error("Can not clone function: Id {}, with name tag {}", id, tag);
      throw new WebApplicationException(e);
    }
  }


  // util functions for clone anomaly functions

  /**
   * Parse function ids to be cloned and clone name tags together
   * the length should be aligned otherwise name tags are all empty
   * or all replace with the first name tag (if length == 1)
   *
   * @param functionIds ',' separated string with numbers representing function ids to be cloned
   * @param nameTags ',' separated string with strings representing name tags for clone functions
   * @return a HashMap from function id to be cloned and the new name tag
   */
  private Map<Long, String> parseFunctionIdsAndNameTags(String functionIds, String nameTags) {
    List<Long> functionIdsList = new ArrayList<>();
    if (StringUtils.isNotBlank(functionIds)) {
      String[] tokens = functionIds.split(",");
      for (String token : tokens) {
        functionIdsList.add(Long.valueOf(token));
      }
    }

    int len = functionIdsList.size();

    List<String> nameTagList = new ArrayList<>();
    if (StringUtils.isNotBlank(nameTags)) {
      String[] tags = nameTags.split(",");
      if (tags.length == 1) {
        for (int i = 0; i < len; i++) {
          nameTagList.add(tags[0]);
          LOG.debug("only 1 tag, use the tag for all function");
        }
      } else {
        if (tags.length == len) {
          for (String tag : tags) {
            nameTagList.add(tag);
          }
        } else {
          LOG.debug("tag list and function id list does not mach, use empty strings as tags");
          for (int i = 0; i < len; i++) {
            nameTagList.add("");
          }
        }
      }
    }

    LOG.info("function ids set: {}", functionIds);
    LOG.info("name tag set: {}", nameTagList);
    // Construct Return results
    HashMap<Long, String> IdNameTagMap = new HashMap<>();
    for (int i=0; i < len; i++) {
      IdNameTagMap.put(functionIdsList.get(i), nameTagList.get(i));
    }
    return IdNameTagMap;
  }

  /**
   * clone Anomaly Function by Id to a function name appended with a name tag
   *
   * @param id function id to be cloned
   * @param cloneNameTag an name tag that will append to original function name as serve as the cloned function name
   * @param doCloneAnomaly does the associated anomaly instances will be clone to new function
   * @return id of the clone function
   */
  public Long cloneAnomalyFunctionById(long id, String cloneNameTag, boolean doCloneAnomaly) throws Exception {
    // get anomaly function definition
    AnomalyFunctionDTO anomalyFunction = anomalyFunctionDAO.findById(id);
    // if cannot find then return
    if (anomalyFunction == null) {
      // LOG and exit
      LOG.error("Anomaly Function With id [{}] does not found", id);
      return null;
    }
    String functionName = anomalyFunction.getFunctionName();
    String newFunctionName = functionName;
    if (cloneNameTag != null && cloneNameTag.length() > 0) {
      newFunctionName = newFunctionName + "_" + cloneNameTag;  // update with input clone function name
    } else {
      newFunctionName = newFunctionName + "_" + "clone_0"; // set default clone function name
    }
    LOG.info("Try to clone anomaly Function Id: {}, Name: {}, to new Name: {}", id, functionName, newFunctionName);
    anomalyFunction.setFunctionName(newFunctionName);
    anomalyFunction.setActive(false);  // deactivate the function
    anomalyFunction.setId(null);
    long newId = anomalyFunctionDAO.save(anomalyFunction);  // exception should be handled when have duplicate name
    LOG.info("clone function id: {}, name: {}  to id: {}, name: {}", id, functionName, newId, newFunctionName);

    if (doCloneAnomaly) {
      cloneAnomalyInstances(id, newId);
    }
    return newId;
  }

  /**
   * Clone merged anomaly instances of one function to another
   *   1. get all merged anomaly instances with AnomalyFunctionId = srcId
   *   2. set the associated anomalyFunctionId = destId
   *   3. save the modified anomaly instances
   * @param srcId the source function Id with anomalies to be cloned.
   * @param destId the destination function Id which source anomalies to be cloned to.
   * @return boolean to indicate if the clone is success or not
   */
  public Boolean cloneAnomalyInstances(Long srcId, Long destId) {

    // make sure both function can be identified by IDs

    AnomalyFunctionDTO srcAnomalyFunction = anomalyFunctionDAO.findById(srcId);
    // if cannot find then return
    if (srcAnomalyFunction == null) {
      // LOG and exit
      LOG.error("Source Anomaly Function With id [{}] does not found", srcId);
      return false;
    }

    AnomalyFunctionDTO destAnomalyFunction = anomalyFunctionDAO.findById(destId);
    // if cannot find then return
    if (destAnomalyFunction == null) {
      // LOG and exit
      LOG.error("Destination Anomaly Function With id [{}] does not found", destId);
      return false;
    }

    LOG.info("clone merged anomaly results from source anomaly function id {} to id {}", srcId, destId);

    List<MergedAnomalyResultDTO> mergedAnomalyResultDTOs = mergedAnomalyResultDAO.findByFunctionId(srcId);
    if (mergedAnomalyResultDTOs == null) {
      LOG.error("No merged anomaly results found for anomaly function Id: {}", srcId);
      return false;
    }

    for (MergedAnomalyResultDTO mergedAnomalyResultDTO : mergedAnomalyResultDTOs) {
      long oldId = mergedAnomalyResultDTO.getId();
      mergedAnomalyResultDTO.setId(null);  // clean the Id, then will create a new Id when save
      mergedAnomalyResultDTO.setRawAnomalyIdList(null);
      mergedAnomalyResultDTO.setFunctionId(destId);
      long newId = mergedAnomalyResultDAO.save(mergedAnomalyResultDTO);
      LOG.debug("clone merged anomaly {} to {}", oldId, newId);
    }
    return true;
  }

}