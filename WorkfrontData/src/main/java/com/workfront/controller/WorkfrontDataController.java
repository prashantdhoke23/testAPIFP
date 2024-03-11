package com.workfront.controller;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Year;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.StreamSupport;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.workfront.service.WorkfrontDataService;

@RestController
public class WorkfrontDataController {
	

    private static final String apiKey = "6gjwtpuicwgvgwal9z5dws2m0qz3fl3u";
   

    @Autowired
    private WorkfrontDataService workfrontDataService;

//    @GetMapping("/dashboardWeeks")
//    public ResponseEntity<Object> getWeekDashboardData() {
////        String apiKey = headers.getFirst("apiKey");
////
////        if (!VALID_API_KEY.equals(apiKey)) {
////            return ResponseEntity.badRequest().body("Invalid API key");
////        }
//
//        Object rawData = workfrontDataService.fetchRawUserData(apiKey);
//
//        if (rawData != null) {
//            Object transformedData = workfrontDataService.transformData(rawData, apiKey);
//            return ResponseEntity.ok(transformedData);
//        } else {
//            return ResponseEntity.status(500).body("Failed to fetch user data");
//        }
//    }
    
    
    @GetMapping("/dashboardMonths")
    public ResponseEntity<Object> getDashboardMonthsData() {
//        String apiKey = headers.getFirst("apiKey");
//
//        if (!VALID_API_KEY.equals(apiKey)) {
//            return ResponseEntity.badRequest().body("Invalid API key");
//        }
        
        Object userData = workfrontDataService.fetchRawUserData(apiKey);

        if (userData != null) {
            ObjectMapper userObjectMapper = new ObjectMapper();
            try {
                JsonNode userJsonNode = userObjectMapper.readTree(userObjectMapper.writeValueAsString(userData));
                JsonNode userArray = userJsonNode.get("data");

                Map<String, List<ObjectNode>> responseDataByYear = new HashMap<>();

                // Iterate over the years from 2022 to the current year
                for (int year = Year.now().getValue(); year >= 2022; year--)  {
                    List<ObjectNode> responseList = new ArrayList<>();

                    for (JsonNode userNode : userArray) {
                        String userId = userNode.get("ID").asText();
                        Object dashboardData = workfrontDataService.getDashboardDataForUser(apiKey, userId);

                        if (dashboardData != null) {
                            ObjectNode responseObject = workfrontDataService.calculateMetricsAndCreateResponse(apiKey, userNode, dashboardData, year);
                            responseList.add(responseObject);
                        }
                    }

                    responseDataByYear.put(String.valueOf(year), responseList);
                }

                ObjectNode response = userObjectMapper.createObjectNode();
                response.set("data", userObjectMapper.valueToTree(responseDataByYear));

                return ResponseEntity.ok(response);

            } catch (JsonProcessingException e) {
                e.printStackTrace();
                return ResponseEntity.status(500).body("Error processing user JSON data");
            }
        }

        return ResponseEntity.ok(userData);
    }
        
}