package io.mosip.biosdk.client.impl.spec_1_0;

import static io.mosip.biosdk.client.constant.AppConstants.LOGGER_IDTYPE;
import static io.mosip.biosdk.client.constant.AppConstants.LOGGER_SESSIONID;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import io.mosip.biosdk.client.utils.*;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import io.mosip.biosdk.client.config.LoggerConfig;
import io.mosip.biosdk.client.dto.CheckQualityRequestDto;
import io.mosip.biosdk.client.dto.ConvertFormatRequestDto;
import io.mosip.biosdk.client.dto.ErrorDto;
import io.mosip.biosdk.client.dto.ExtractTemplateRequestDto;
import io.mosip.biosdk.client.dto.InitRequestDto;
import io.mosip.biosdk.client.dto.MatchRequestDto;
import io.mosip.biosdk.client.dto.RequestDto;
import io.mosip.biosdk.client.dto.SegmentRequestDto;
import io.mosip.kernel.biometrics.constant.BiometricType;
import io.mosip.kernel.biometrics.entities.BiometricRecord;
import io.mosip.kernel.biometrics.model.MatchDecision;
import io.mosip.kernel.biometrics.model.QualityCheck;
import io.mosip.kernel.biometrics.model.Response;
import io.mosip.kernel.biometrics.model.SDKInfo;
import io.mosip.kernel.biometrics.spi.IBioApiV2;
import io.mosip.kernel.core.logger.spi.Logger;

/**
 * The Class BioApiImpl.
 * 
 * @author Sanjay Murali
 * @author Manoj SP
 * @author Ankit
 * @author Loganathan Sekar
 * 
 */
public class Client_V_1_0 implements IBioApiV2 {

	private static final String FORMAT_SUFFIX = ".format";

	private static final String DEFAULT = "default";

	private static final String FORMAT_URL_PREFIX = "format.url.";

	private static final String PARAMETER_PREFIX = "config.parameter.";

	private static final String MOSIP_BIOSDK_SERVICE = "mosip_biosdk_service";

	private static Logger logger = LoggerConfig.logConfig(Client_V_1_0.class);

	private static final String VERSION = "1.0";

	TypeReference<List<ErrorDto>> errorDtoListTypeRef = new TypeReference<List<ErrorDto>>(){};

	private Map<String, String> sdkUrlsMap;

	@Override
	public SDKInfo init(Map<String, String> initParams) {
		sdkUrlsMap = getSdkUrls(initParams);
		setConfigParameters(initParams);
		List<SDKInfo> sdkInfos = sdkUrlsMap.values()
											.stream()
											.map(sdkUrl -> initForSdkUrl(initParams, sdkUrl))
											.collect(Collectors.toList());
		return getAggregatedSdkInfo(sdkInfos);
	}

	private void setConfigParameters(Map<String, String> initParams) {
		Map<String, String> parametersMap = new HashMap<>(initParams.entrySet()
				.stream()
				.filter(entry -> entry.getKey().contains(PARAMETER_PREFIX))
				.collect(Collectors.toMap(entry -> entry.getKey()
						.substring(PARAMETER_PREFIX.length()), Entry::getValue)));

		for(Map.Entry<String, String> map : parametersMap.entrySet()) {
			System.setProperty(map.getKey(), map.getValue());
			logger.debug(LOGGER_SESSIONID, LOGGER_IDTYPE, map.getKey() ,  map.getValue());
		}
	}

	private SDKInfo getAggregatedSdkInfo(List<SDKInfo> sdkInfos) {
		SDKInfo sdkInfo;
		if(!sdkInfos.isEmpty()) {
			sdkInfo = sdkInfos.get(0);
			if(sdkInfos.size() == 1) {
				return sdkInfo;
			} else {
				return getAggregatedSdkInfo(sdkInfos, sdkInfo);
			}
		} else {
			sdkInfo = null;
		}
		return sdkInfo;
	}

	private SDKInfo getAggregatedSdkInfo(List<SDKInfo> sdkInfos, SDKInfo sdkInfo) {
		String organization = sdkInfo.getProductOwner() == null ? null : sdkInfo.getProductOwner().getOrganization();
		String type = sdkInfo.getProductOwner() == null ? null : sdkInfo.getProductOwner().getType();
		SDKInfo aggregatedSdkInfo = new SDKInfo(sdkInfo.getApiVersion(), sdkInfo.getSdkVersion(), organization, type);
		sdkInfos.forEach(info -> addOtherSdkInfoDetails(info, aggregatedSdkInfo));
		return aggregatedSdkInfo;
	}

	private void addOtherSdkInfoDetails(SDKInfo sdkInfo, SDKInfo aggregatedSdkInfo) {
		if(sdkInfo.getOtherInfo() != null) {
			aggregatedSdkInfo.getOtherInfo().putAll(sdkInfo.getOtherInfo());
		}
		if(sdkInfo.getSupportedMethods() != null) {
			aggregatedSdkInfo.getSupportedMethods().putAll(sdkInfo.getSupportedMethods());
		}
		if(sdkInfo.getSupportedModalities() != null) {
			List<BiometricType> supportedModalities = aggregatedSdkInfo.getSupportedModalities();
			supportedModalities.addAll(sdkInfo.getSupportedModalities()
					.stream()
					.filter(s -> !supportedModalities.contains(s))
					.collect(Collectors.toList()));
		}
	}

	private SDKInfo initForSdkUrl(Map<String, String> initParams, String sdkServiceUrl) {
		SDKInfo sdkInfo = null;
		try {
			InitRequestDto initRequestDto = new InitRequestDto();
			initRequestDto.setInitParams(initParams);

			RequestDto requestDto = generateNewRequestDto(initRequestDto);
			logger.debug(LOGGER_SESSIONID, LOGGER_IDTYPE, "HTTP url: ", sdkServiceUrl+"/init");
			ResponseEntity<?> responseEntity = Util.restRequest(sdkServiceUrl+"/init", HttpMethod.POST, MediaType.APPLICATION_JSON, requestDto, null, String.class);
			if(!responseEntity.getStatusCode().is2xxSuccessful()){
				logger.debug(LOGGER_SESSIONID, LOGGER_IDTYPE, "HTTP status: ", responseEntity.getStatusCode().toString());
				throw new RuntimeException("HTTP status: "+responseEntity.getStatusCode().toString());
			}
			Object body = responseEntity.getBody();
			if (body == null) {
				throw new NullPointerException("Response body is null");
			}
			String responseBody = body.toString();
            JSONParser parser = new JSONParser();
			JSONObject js = (JSONObject) parser.parse(responseBody);

            /* Error handler */
            errorHandler(js.get("errors") != null ? Util.getObjectMapper().readValue(js.get("errors").toString(), errorDtoListTypeRef) : null);

			sdkInfo = Util.getObjectMapper().readValue(js.get("response").toString(), new TypeReference<SDKInfo>() {});
		} catch (ParseException | JsonProcessingException e) {
			logger.error(LOGGER_SESSIONID, LOGGER_IDTYPE, "error", e);
			throw new RuntimeException(e);
		}
        return sdkInfo;
	}

	private Map<String, String> getSdkUrls(Map<String, String> initParams) {
		Map<String, String> sdkUrls = new HashMap<>(initParams.entrySet()
				.stream()
				.filter(entry -> entry.getKey().contains(FORMAT_URL_PREFIX))
				.collect(Collectors.toMap(entry -> entry.getKey()
						.substring(FORMAT_URL_PREFIX.length()), Entry::getValue)));
		if(!sdkUrls.containsKey(DEFAULT)) {
			//If default is not specified in configuration, try getting it from env.
			String defaultSdkServiceUrl = getDefaultSdkServiceUrlFromEnv();
			if(defaultSdkServiceUrl != null) {
				sdkUrls.put(DEFAULT, defaultSdkServiceUrl);
			}
		}
		
		//There needs a default URL to be used when no format is specified.
		if(!sdkUrls.containsKey(DEFAULT) && !sdkUrls.isEmpty()) {
			//Take any first url and set it to default
			sdkUrls.put(DEFAULT, sdkUrls.values().iterator().next());
		}
		
		if(sdkUrls.isEmpty()) {
			throw new IllegalStateException("No valid sdk service url configured");
		}
		return sdkUrls;
	}
	
	private String getSdkServiceUrl(BiometricType modality, Map<String, String> flags) {
		if(modality != null) {
			String key = modality.name() + FORMAT_SUFFIX;
			if(flags != null) {
				Optional<String> formatFromFlag = flags.entrySet()
							.stream()
							.filter(e -> e.getKey().equalsIgnoreCase(key))
							.findAny()
							.map(Entry::getValue);
				if(formatFromFlag.isPresent()) {
					String format = formatFromFlag.get();
					Optional<String> urlForFormat = sdkUrlsMap.entrySet()
							.stream()
							.filter(e -> e.getKey().equalsIgnoreCase(format))
							.findAny()
							.map(Entry::getValue);
					if(urlForFormat.isPresent()) {
						return urlForFormat.get();
					}
				}
			}
		}
		return getDefaultSdkServiceUrl();
	}
	
	private String getDefaultSdkServiceUrl() {
		return sdkUrlsMap.get(DEFAULT);
	}

	private String getDefaultSdkServiceUrlFromEnv() {
		return System.getenv(MOSIP_BIOSDK_SERVICE);
	}

	@Override
	public Response<QualityCheck> checkQuality(BiometricRecord sample, List<BiometricType> modalitiesToCheck, Map<String, String> flags) {
		Response<QualityCheck> response = new Response<>();
		response.setStatusCode(200);
		QualityCheck qualityCheck = null;
		try {
			CheckQualityRequestDto checkQualityRequestDto = new CheckQualityRequestDto();
			checkQualityRequestDto.setSample(sample);
			checkQualityRequestDto.setModalitiesToCheck(modalitiesToCheck);
			checkQualityRequestDto.setFlags(flags);
			RequestDto requestDto = generateNewRequestDto(checkQualityRequestDto);
			String url = getSdkServiceUrl(modalitiesToCheck.get(0), flags)+"/check-quality";
			logger.debug(LOGGER_SESSIONID, LOGGER_IDTYPE, "HTTP url: ", url);
			ResponseEntity<?> responseEntity = Util.restRequest(url, HttpMethod.POST, MediaType.APPLICATION_JSON, requestDto, null, String.class);
			if(!responseEntity.getStatusCode().is2xxSuccessful()){
				logger.debug(LOGGER_SESSIONID, LOGGER_IDTYPE, "HTTP status: ", responseEntity.getStatusCode().toString());
				throw new RuntimeException("HTTP status: "+responseEntity.getStatusCode().toString());
			}
			Object body = responseEntity.getBody();
			if (body == null) {
				throw new NullPointerException("Response body is null");
			}
			String responseBody = body.toString();
			JSONParser parser = new JSONParser();
			JSONObject js = (JSONObject) parser.parse(responseBody);
			JSONObject responseJson =(JSONObject)  ((JSONObject) js.get("response")).get("response");

			/* Error handler */
			errorHandler(js.get("errors") != null ? Util.getObjectMapper().readValue(js.get("errors").toString(), errorDtoListTypeRef) : null);

			qualityCheck = Util.getObjectMapper().readValue(responseJson.toString(), new TypeReference<QualityCheck>() {});
		} catch (ParseException | JsonProcessingException e) {
			logger.error(LOGGER_SESSIONID, LOGGER_IDTYPE, "error", e);
			throw new RuntimeException(e);
		}
		response.setResponse(qualityCheck);
		return response;
	}

	@Override
	public Response<MatchDecision[]> match(BiometricRecord sample, BiometricRecord[] gallery,
										   List<BiometricType> modalitiesToMatch, Map<String, String> flags) {
		Response<MatchDecision[]> response = new Response<>();
		try {
			MatchRequestDto matchRequestDto = new MatchRequestDto();
			matchRequestDto.setSample(sample);
			matchRequestDto.setGallery(gallery);
			matchRequestDto.setModalitiesToMatch(modalitiesToMatch);
			matchRequestDto.setFlags(flags);

			RequestDto requestDto = generateNewRequestDto(matchRequestDto);
			String url = getSdkServiceUrl(modalitiesToMatch.get(0), flags)+"/match";
			logger.debug(LOGGER_SESSIONID, LOGGER_IDTYPE, "HTTP url: ", url);
			ResponseEntity<?> responseEntity = Util.restRequest(url, HttpMethod.POST, MediaType.APPLICATION_JSON, requestDto, null, String.class);
			if(!responseEntity.getStatusCode().is2xxSuccessful()){
				logger.debug(LOGGER_SESSIONID, LOGGER_IDTYPE, "HTTP status: ", responseEntity.getStatusCode().toString());
				throw new RuntimeException("HTTP status: "+responseEntity.getStatusCode().toString());
			}
			Object body = responseEntity.getBody();
			if (body == null) {
				throw new NullPointerException("Response body is null");
			}
			String responseBody = body.toString();
			logger.info(LOGGER_SESSIONID, LOGGER_IDTYPE, "HTTP response: ", responseBody);
			JSONParser parser = new JSONParser();
			JSONObject js = (JSONObject) parser.parse(responseBody);

			/* Error handler */
			errorHandler(js.get("errors") != null ? Util.getObjectMapper().readValue(js.get("errors").toString(), errorDtoListTypeRef) : null);

			JSONObject jsonResponse = (JSONObject) parser.parse(js.get("response").toString());
			response.setStatusCode(
				jsonResponse.get("statusCode") != null ? ((Long)jsonResponse.get("statusCode")).intValue() : null
			);
			response.setStatusMessage(
				jsonResponse.get("statusMessage") != null ? jsonResponse.get("statusMessage").toString() : ""
			);
			response.setResponse(
					jsonResponse.get("response") != null ? Util.getObjectMapper().readValue(jsonResponse.get("response").toString(), new TypeReference<MatchDecision[]>() {}) : null
			);
		} catch (ParseException | JsonProcessingException e) {
			logger.error(LOGGER_SESSIONID, LOGGER_IDTYPE, "error", e);
			throw new RuntimeException(e);
		}
        return response;
	}

	@Override
	public Response<BiometricRecord> extractTemplate(BiometricRecord sample, List<BiometricType> modalitiesToExtract, Map<String, String> flags) {
		Response<BiometricRecord> response = new Response<>();
		try {
			ExtractTemplateRequestDto extractTemplateRequestDto = new ExtractTemplateRequestDto();
			extractTemplateRequestDto.setSample(sample);
			extractTemplateRequestDto.setModalitiesToExtract(modalitiesToExtract);
			extractTemplateRequestDto.setFlags(flags);

			RequestDto requestDto = generateNewRequestDto(extractTemplateRequestDto);
			String url = getSdkServiceUrl(modalitiesToExtract, flags)+"/extract-template";
			logger.debug(LOGGER_SESSIONID, LOGGER_IDTYPE, "HTTP url: ", url);
			ResponseEntity<?> responseEntity = Util.restRequest(url, HttpMethod.POST, MediaType.APPLICATION_JSON, requestDto, null, String.class);
			if(!responseEntity.getStatusCode().is2xxSuccessful()){
				logger.debug(LOGGER_SESSIONID, LOGGER_IDTYPE, "HTTP status: ", responseEntity.getStatusCode().toString());
				throw new RuntimeException("HTTP status: "+responseEntity.getStatusCode().toString());
			}
			convertAndSetResponseObject(response, responseEntity);

		} catch (ParseException | JsonProcessingException e) {
			logger.error(LOGGER_SESSIONID, LOGGER_IDTYPE, "error", e);
			throw new RuntimeException(e);
		}
        return response;
	}

	private String getSdkServiceUrl(List<BiometricType> modalitiesToExtract, Map<String, String> flags) {
		if(modalitiesToExtract != null && !modalitiesToExtract.isEmpty()) {
			return getSdkServiceUrl(modalitiesToExtract.get(0), flags);
		} else {
			Set<String> keySet = flags.keySet();
			for(String key: keySet) {
				if(key.toLowerCase().contains(BiometricType.FINGER.name().toLowerCase())) {
					return getSdkServiceUrl(BiometricType.FINGER, flags);
				} else if(key.toLowerCase().contains(BiometricType.IRIS.name().toLowerCase())) {
					return getSdkServiceUrl(BiometricType.IRIS, flags);
				} else if(key.toLowerCase().contains(BiometricType.FACE.name().toLowerCase())) {
					return getSdkServiceUrl(BiometricType.FACE, flags);
				}
			}
		}
		return getDefaultSdkServiceUrl();
	}

	@Override
	public Response<BiometricRecord> segment(BiometricRecord biometricRecord, List<BiometricType> modalitiesToSegment, Map<String, String> flags) {
		Response<BiometricRecord> response = new Response<>();
		try {
			SegmentRequestDto segmentRequestDto = new SegmentRequestDto();
			segmentRequestDto.setSample(biometricRecord);
			segmentRequestDto.setModalitiesToSegment(modalitiesToSegment);
			segmentRequestDto.setFlags(flags);

			RequestDto requestDto = generateNewRequestDto(segmentRequestDto);
			String url = getSdkServiceUrl(modalitiesToSegment.get(0), flags)+"/segment";
			logger.debug(LOGGER_SESSIONID, LOGGER_IDTYPE, "HTTP url: ", url);
			ResponseEntity<?> responseEntity = Util.restRequest(url, HttpMethod.POST, MediaType.APPLICATION_JSON, requestDto, null, String.class);
			if(!responseEntity.getStatusCode().is2xxSuccessful()){
				logger.debug(LOGGER_SESSIONID, LOGGER_IDTYPE, "HTTP status: ", responseEntity.getStatusCode().toString());
				throw new RuntimeException("HTTP status: "+responseEntity.getStatusCode().toString());
			}
			convertAndSetResponseObject(response, responseEntity);
		} catch (ParseException | JsonProcessingException e) {
			logger.error(LOGGER_SESSIONID, LOGGER_IDTYPE, "error", e);
			throw new RuntimeException(e);
		}
		return response;
	}

	private void convertAndSetResponseObject(Response<BiometricRecord> response, ResponseEntity<?> responseEntity) throws ParseException, JsonProcessingException {
		Object body = responseEntity.getBody();
		if (body == null) {
			throw new NullPointerException("Response body is null");
		}
		String responseBody = body.toString();
		JSONParser parser = new JSONParser();
		JSONObject js = (JSONObject) parser.parse(responseBody);

		/* Error handler */
		errorHandler(js.get("errors") != null ? Util.getObjectMapper().readValue(js.get("errors").toString(), errorDtoListTypeRef) : null);

		JSONObject jsonResponse = (JSONObject) parser.parse(js.get("response").toString());
		response.setStatusCode(
				jsonResponse.get("statusCode") != null ? ((Long)jsonResponse.get("statusCode")).intValue() : null
		);
		response.setStatusMessage(
				jsonResponse.get("statusMessage") != null ? jsonResponse.get("statusMessage").toString() : ""
		);
		response.setResponse(
				jsonResponse.get("response") != null ? Util.getObjectMapper().readValue(jsonResponse.get("response").toString(), new TypeReference<BiometricRecord>() {}) : null
		);
	}

	@Override
	@Deprecated
	public BiometricRecord convertFormat(BiometricRecord sample, String sourceFormat, String targetFormat,
			Map<String, String> sourceParams, Map<String, String> targetParams, List<BiometricType> modalitiesToConvert) {
		BiometricRecord resBiometricRecord = null;
		try {
			ConvertFormatRequestDto convertFormatRequestDto = new ConvertFormatRequestDto();
			convertFormatRequestDto.setSample(sample);
			convertFormatRequestDto.setSourceFormat(sourceFormat);
			convertFormatRequestDto.setTargetFormat(targetFormat);
			convertFormatRequestDto.setSourceParams(sourceParams);
			convertFormatRequestDto.setTargetParams(targetParams);
			convertFormatRequestDto.setModalitiesToConvert(modalitiesToConvert);

			RequestDto requestDto = generateNewRequestDto(convertFormatRequestDto);
			String url = getDefaultSdkServiceUrl()+"/convert-format";
			logger.debug(LOGGER_SESSIONID, LOGGER_IDTYPE, "HTTP url: ", url);
			ResponseEntity<?> responseEntity = Util.restRequest(url, HttpMethod.POST, MediaType.APPLICATION_JSON, requestDto, null, String.class);
			if(!responseEntity.getStatusCode().is2xxSuccessful()){
				logger.debug(LOGGER_SESSIONID, LOGGER_IDTYPE, "HTTP status: ", responseEntity.getStatusCode().toString());
				throw new RuntimeException("HTTP status: "+responseEntity.getStatusCode().toString());
			}
			Object body = responseEntity.getBody();
			if (body == null) {
				throw new NullPointerException("Response body is null");
			}
			String responseBody = body.toString();
			JSONParser parser = new JSONParser();
			JSONObject js = (JSONObject) parser.parse(responseBody);

			/* Error handler */
			errorHandler(js.get("errors") != null ? Util.getObjectMapper().readValue(js.get("errors").toString(), errorDtoListTypeRef) : null);

			resBiometricRecord = Util.getObjectMapper().readValue(js.get("response").toString(), new TypeReference<BiometricRecord>() {});
		} catch (ParseException | JsonProcessingException e) {
			logger.error(LOGGER_SESSIONID, LOGGER_IDTYPE, "error", e);
			throw new RuntimeException(e);
		}
		return resBiometricRecord;
	}
	
	@Override
	public Response<BiometricRecord> convertFormatV2(BiometricRecord sample, String sourceFormat, String targetFormat,
			Map<String, String> sourceParams, Map<String, String> targetParams,
			List<BiometricType> modalitiesToConvert) {
		Response<BiometricRecord> response = new Response<>();
		try {
			ConvertFormatRequestDto convertFormatRequestDto = new ConvertFormatRequestDto();
			convertFormatRequestDto.setSample(sample);
			convertFormatRequestDto.setSourceFormat(sourceFormat);
			convertFormatRequestDto.setTargetFormat(targetFormat);
			convertFormatRequestDto.setSourceParams(sourceParams);
			convertFormatRequestDto.setTargetParams(targetParams);
			convertFormatRequestDto.setModalitiesToConvert(modalitiesToConvert);

			RequestDto requestDto = generateNewRequestDto(convertFormatRequestDto);
			String url = getDefaultSdkServiceUrl()+"/convert-format";
			logger.debug(LOGGER_SESSIONID, LOGGER_IDTYPE, "HTTP url: ", url);
			ResponseEntity<?> responseEntity = Util.restRequest(url, HttpMethod.POST, MediaType.APPLICATION_JSON, requestDto, null, String.class);
			if(!responseEntity.getStatusCode().is2xxSuccessful()){
				logger.debug(LOGGER_SESSIONID, LOGGER_IDTYPE, "HTTP status: ", responseEntity.getStatusCode().toString());
				throw new RuntimeException("HTTP status: "+responseEntity.getStatusCode().toString());
			}
			Object body = responseEntity.getBody();
			if (body == null) {
				throw new NullPointerException("Response body is null");
			}
			String responseBody = body.toString();
			convertAndSetResponseObject(response, responseBody, new TypeReference<BiometricRecord>() {});
		} catch (ParseException | JsonProcessingException e) {
			logger.error(LOGGER_SESSIONID, LOGGER_IDTYPE, "error", e);
			throw new RuntimeException(e);
		}
            return response;
	}

	private <T> void convertAndSetResponseObject(Response<T> response, String responseBody, TypeReference<T> type) throws ParseException, JsonProcessingException {
		JSONParser parser = new JSONParser();
		JSONObject js = (JSONObject) parser.parse(responseBody);

		/* Error handler */
		errorHandler(js.get("errors") != null ? Util.getObjectMapper().readValue(js.get("errors").toString(), errorDtoListTypeRef) : null);


		JSONObject jsonResponse = (JSONObject) parser.parse(js.get("response").toString());
		response.setStatusCode(
				jsonResponse.get("statusCode") != null ? ((Long)jsonResponse.get("statusCode")).intValue() : null
		);
		response.setStatusMessage(
				jsonResponse.get("statusMessage") != null ? jsonResponse.get("statusMessage").toString() : ""
		);
		response.setResponse(
				jsonResponse.get("response") != null ? Util.getObjectMapper().readValue(jsonResponse.get("response").toString(), type) : null
		);
	}

	private RequestDto generateNewRequestDto(Object body) throws JsonProcessingException {
		RequestDto requestDto = new RequestDto();
		requestDto.setVersion(VERSION);
		requestDto.setRequest(Util.base64Encode(Util.getObjectMapper().writeValueAsString(body)));
		return requestDto;
	}

	private void errorHandler(List<ErrorDto> errors) {
		if (errors != null && !errors.isEmpty()) {
			StringBuilder errorMessage = new StringBuilder("Errors encountered:\n");
			for (ErrorDto errorDto : errors) {
				errorMessage.append(errorDto.getCode())
						.append(" ---> ")
						.append(errorDto.getMessage())
						.append("\n");
			}
			throw new RuntimeException(errorMessage.toString());
		}
	}
}
