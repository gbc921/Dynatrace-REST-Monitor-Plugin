package com.realdolmen.dynatrace.restmonitor;

import java.io.StringReader;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.logging.Logger;
import java.util.List;

import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.xml.sax.InputSource;

import com.dynatrace.diagnostics.pdk.MonitorEnvironment;
import com.dynatrace.diagnostics.pdk.MonitorMeasure;
import com.dynatrace.diagnostics.util.modern.StringUtil;
import com.jayway.jsonpath.JsonPath;

import net.minidev.json.JSONObject;
import net.minidev.json.parser.JSONParser;

/**
 * @author heydenb
 *
 */
public class MeasureCapturedValues {

	private static final String METRIC_GROUP = "REST Monitor Measures";
	private static final String METRIC_NAME = "capturedValue";
	private static final String PARAM_XJSONPATH = "xjsonPath";
	private static final String PARAM_CONVERSIONMAP = "conversionMap";

	private static final Logger log = Logger.getLogger(RestMonitor.class.getName());
	private static final String DEFAULT_RESULT = "0.0";

	private MonitorEnvironment monitorEnvironment;

	/**
	 * @param env
	 */
	MeasureCapturedValues(MonitorEnvironment env) {
		log.info("constructor");
		this.monitorEnvironment = env;
	}

	void applyMeasuresToEnvironment(String body, ResponseFormat format) throws RestMonitorConfigurationException{
		Collection<MonitorMeasure> measures;

		log.info("applyMeasuresToEnvironment");

		if ((measures = monitorEnvironment.getMonitorMeasures(METRIC_GROUP, METRIC_NAME)) != null) {
			log.info("measures count " + measures.size());
			for (MonitorMeasure measure : measures){
				String pathExpression = measure.getParameter(PARAM_XJSONPATH);
				String conversionMapJson = measure.getParameter(PARAM_CONVERSIONMAP);

				log.fine("Path Expression: " + pathExpression);
				log.fine("Conversion Map: " + conversionMapJson);
				log.fine("Response Format: " + format.toString());
				log.fine("Response Body: " + body.toString());

				Map<String, Double> conversionMap = null;
				if (!StringUtil.isTrimmedEmpty(conversionMapJson)){
					conversionMap = createConversionMap(conversionMapJson);
				}
				if (format == ResponseFormat.JSON){
					Double value = extractJSONPathValue(body, pathExpression, conversionMap);
					measure.setValue(value);
				}
				else if (format == ResponseFormat.XML){
					Double value = extractXPathValue(body, pathExpression, conversionMap);
					measure.setValue(value);
				}
			}
		}
	}

	/**
	 * Converts the provided JSON string to a Map which can be used for String to Double conversions
	 *
	 * @param json configuration
	 * @return the parsed JSON as Map
	 * @throws RestMonitorConfigurationException
	 */
	Map<String, Double> createConversionMap(String json) throws RestMonitorConfigurationException {
		JSONParser parser = new JSONParser(JSONParser.MODE_PERMISSIVE);
		JSONObject obj = null;
		try {
			obj = (JSONObject)parser.parse(json);
		} catch (Exception e) {
			throw new RestMonitorConfigurationException("ConversionMap parse error: " + e.getMessage());
		}

		Map<String, Double> map = new HashMap<String, Double>();
		try {
			obj.forEach(new BiConsumer<String, Object>() {

				@Override
				public void accept(String key, Object mapped) {
					Double value = null;
					if (mapped instanceof Double){
						value = (Double)mapped;
					}
					else if (mapped instanceof Long){
						value = ((Long)mapped).doubleValue();
					}
					else if (mapped instanceof String){
						value = Double.valueOf((String)mapped);
					}
					map.put(key, value);

				}
			});
		}catch (NumberFormatException e){
			throw new RestMonitorConfigurationException("ConversionMap parse error: " + e.getMessage());
		}

		return map;
	}

	/**
	 *
	 * @param jsonBody
	 * @param jsonPathExpression
	 * @param conversionMap
	 * @return
	 */
	Double extractJSONPathValue(String jsonBody, String jsonPathExpression, Map<String, Double> conversionMap) throws RestMonitorConfigurationException{
		Object obj = null;
		JsonPath jsonPath = JsonPath.compile(jsonPathExpression);
		
		if (jsonBody.isEmpty()){
			throw new RestMonitorConfigurationException("json response was empty!");
		}
		if (!jsonPath.isDefinite()){
			log.fine("jsonPathIsDefinite");
			List<Object> objList = jsonPath.read(jsonBody);
			log.finer("object list" + objList.toString());
			//workaround to get only one value to not deal with this now
			try {
				obj = objList.get(0);
				log.finer("object list 1st position" + objList.get(0).toString());
			} catch (Exception e) {
				throw new RestMonitorConfigurationException("JSON XPath parse error: " + e.getMessage());
			}
		}

		// check if it was not set yet, and try to set it
		if (obj == null){
			try {
				obj = jsonPath.read(jsonBody);
			} catch (Exception e) {
				throw new RestMonitorConfigurationException("JSON read error: " + e.getMessage());
			}
		}

		if (conversionMap==null){
			if (obj instanceof Double){
				return (Double)obj;
			}
			if (obj instanceof Long){
				return ((Long)obj).doubleValue();
			}
			if (obj instanceof Integer){
				return ((Integer)obj).doubleValue();
			}
			if (obj instanceof Boolean){
				return ((Boolean)obj)?1d:0d;
			}
			if (obj instanceof String){
				return Double.valueOf((String)obj);
			}
		}

		String key = obj.toString();
		return conversionMap.get(key);
	}

	/**
	 * Select the xPathExpression from the xmlBody.
	 * If the conversionMap is null, the result is a double if it can be parsed to a double.
	 * If the conversionMap is provided, the result must be a string that is mapped to a double via the conversionMap
	 *
	 * @param xmlBody
	 * @param xPathExpression
	 * @param conversionMap
	 * @return
	 * @throws RestMonitorConfigurationException
	 */
	Double extractXPathValue(String xmlBody, String xPathExpression, Map<String, Double> conversionMap) throws RestMonitorConfigurationException{
		InputSource inputSource = new InputSource( new StringReader(xmlBody));
		XPathFactory xPathfactory = XPathFactory.newInstance();
		XPath xpath = xPathfactory.newXPath();
		String result = DEFAULT_RESULT;
		try {
			XPathExpression expr = xpath.compile(xPathExpression);
			result = expr.evaluate(inputSource);
		} catch (XPathExpressionException e) {
			throw new RestMonitorConfigurationException("XPath Expression error: " + e.getMessage(), e);
		}
		if (conversionMap == null){
			if (result==null || result.length()==0){
				result = DEFAULT_RESULT;
			}
			try {
				return Double.parseDouble(result);
			}
			catch (NumberFormatException e){
				throw new RestMonitorConfigurationException("Result could not be parsed as a number and there is no conversionMap provided");
			}
		}
		Double d = conversionMap.get(result);
		if (d==null){
			return -1d;
		}
		return d;
	}

}
