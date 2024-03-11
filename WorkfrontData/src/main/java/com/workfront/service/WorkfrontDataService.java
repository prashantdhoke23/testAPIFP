package com.workfront.service;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.StreamSupport;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

@Service
public class WorkfrontDataService {
	
	@Value("${workfront.api.key}")
    private String apiKey;
	
	public Object fetchRawUserData(String apiKey) {
        String userApiUrl = "https://foolproof.my.workfront.com/attask/api/v17.0/user/search?$$FIRST=0&$$LIMIT=1000&fields=name,title,role,fte,workTime,country&isActive=true&apiKey=" + apiKey;
        return makeHttpRequestWithRetry(userApiUrl, apiKey);
    }
	
	public Object getDashboardDataForUser(String apiKey, String userId) {
        String dashboardApiUrl = "https://foolproof.my.workfront.com/attask/api/v17.0/user/search?$$FIRST=0&$$LIMIT=1000&fields=name,role,*,reservedTimes:*&ID=" + userId + "&apiKey=" + apiKey;
        return makeHttpRequestWithRetry(dashboardApiUrl,apiKey);
    }

        public ObjectNode calculateMetricsAndCreateResponse(String apiKey, JsonNode userNode, Object dashboardData, int year) {
            ObjectMapper objectMapper = new ObjectMapper();
            ObjectNode responseObject = objectMapper.createObjectNode();

            double[] actualHoursArray = new double[12];
            double[] availableHoursArray = new double[12];
            double[] plannedHoursArray = new double[12];

            double fte = userNode.get("fte").asDouble();
            String userId = userNode.get("ID").asText();
            double workTime=userNode.get("workTime").asDouble();
            String country=userNode.get("country").asText();

            try {
                JsonNode dashboardJsonNode = objectMapper.readTree(objectMapper.writeValueAsString(dashboardData));

                // Extract user details from the response
                String roleName = "";
                JsonNode roleNode = dashboardJsonNode.path("data").get(0).path("role");
                if (roleNode != null && !roleNode.isNull()) {
                    roleName = roleNode.path("name").asText();
                }

                String userName = dashboardJsonNode.path("data").get(0).path("name").asText();

                if (userName == null || userName.isEmpty()) {
                    userName = "";
                }

                JsonNode reservedTimesArray = dashboardJsonNode.path("data").get(0).path("reservedTimes");

                double[] reservedTimeByMonth = new double[12];

                String scheduleId = dashboardJsonNode.path("data").get(0).path("scheduleID").asText();

                String scheduleApiUrl = "https://foolproof.my.workfront.com/attask/api/v17.0/sched/search?$$FIRST=0&$$LIMIT=2000&fields=*,nonWorkDays:*&ID=" + scheduleId + "&apiKey=" + apiKey;
                Object scheduleData = makeHttpRequestWithRetry(scheduleApiUrl, apiKey);

                if (scheduleData != null) {
                    JsonNode scheduleJsonNode = objectMapper.readTree(objectMapper.writeValueAsString(scheduleData));
                    JsonNode nonWorkDaysArray = scheduleJsonNode.path("data").get(0).path("nonWorkDays");

                    List<String> nonWorkDatesList = new ArrayList<>();

                    for (JsonNode nonWorkDayNode : nonWorkDaysArray) {
                        String nonWorkDate = nonWorkDayNode.path("nonWorkDate").asText();
                        nonWorkDatesList.add(nonWorkDate);
                    }

                    String[] nonWorkDatesArray = nonWorkDatesList.toArray(new String[0]);

                    List<String> nonWorkDatesListThisYear = nonWorkDatesList.stream()
                            .filter(date -> date.startsWith(String.valueOf(year)))
                            .collect(Collectors.toList());
                    String[] nonWorkDatesArrayThisYear = nonWorkDatesListThisYear.toArray(new String[0]);

                    double workingHoursPerDay = (country.equalsIgnoreCase("UK")) ? 7.5 : 8.0;

                    for (JsonNode reservedTimesNode : reservedTimesArray) {
                        String startDateString = reservedTimesNode.path("startDate").asText();
                        String endDateString = reservedTimesNode.path("endDate").asText();

                        LocalDateTime startDate = LocalDateTime.parse(startDateString, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss:SSSZ"));
                        LocalDateTime endDate = LocalDateTime.parse(endDateString, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss:SSSZ"));

                        if (startDate.getYear() == year || endDate.getYear() == year) {
                            for (int month = 1; month <= 12; month++) {
                                if (startDate.getMonthValue() == month || endDate.getMonthValue() == month) {
                                    LocalDateTime monthStart = LocalDateTime.of(year, month, 1, 0, 0);
                                    LocalDateTime monthEnd = monthStart.plusMonths(1).minusNanos(1);

                                    LocalDateTime overlapStart = startDate.isAfter(monthStart) ? startDate : monthStart;
                                    LocalDateTime overlapEnd = endDate.isBefore(monthEnd) ? endDate : monthEnd;

                                    long minutes = ChronoUnit.MINUTES.between(overlapStart, overlapEnd);
                                    double days = minutes / (24.0 * 60.0);

                                    for (int i = 0; i < days; i++) { 
                                        LocalDateTime currentDateTime = overlapStart.plusDays(i);

                                        if (Arrays.asList(nonWorkDatesArrayThisYear).contains(currentDateTime.toLocalDate().toString())) {
                                            continue;
                                        }

                                        if (currentDateTime.getDayOfWeek() == DayOfWeek.SATURDAY || currentDateTime.getDayOfWeek() == DayOfWeek.SUNDAY) {
                                            continue;
                                        }

                                        reservedTimeByMonth[month - 1] += 1.0;
                                    }
                                }
                            }
                        }
                    }

                    for (int month = 1; month <= 12; month++) {
                        final int currentMonth = month;
                        LocalDate firstDayOfMonth = LocalDate.of(year, month, 1);
                        LocalDate lastDayOfMonth = LocalDate.of(year, month, firstDayOfMonth.lengthOfMonth());

                        long workingDays = calculateWorkingDays(firstDayOfMonth, lastDayOfMonth);
                        final int finalYear = year;

                        long nonWorkingDays = Arrays.stream(nonWorkDatesArrayThisYear)
                                .filter(date -> date.startsWith(String.format("%d-%02d", finalYear, currentMonth)))
                                .count();
                        double availableHours = (workingDays - reservedTimeByMonth[month - 1] - nonWorkingDays) * workingHoursPerDay;
                        availableHoursArray[month - 1] = availableHours;
                    }

                    for (int month = 1; month <= 12; month++) {
                        double actualHoursForMonth = getActualHoursForMonth(dashboardJsonNode.path("data").get(0), apiKey, year, month - 1);
                        actualHoursArray[month - 1] = actualHoursForMonth / 60;
                    }

                    for (int month = 1; month <= 12; month++) {
                        LocalDate[] dateRanges = getMonthDateRange(year, month);
                        double plannedHoursMonths = getPlannedHoursForWeekWithRetry(userId, apiKey, dateRanges);
                        plannedHoursArray[month - 1] = plannedHoursMonths;
                    }

//                    targetUtilization = fte + ", " + country;

                } 

                responseObject.put("Resource Name", userName);
                responseObject.put("Role", roleName);
                responseObject.put("FTE", fte);
                responseObject.put("Work Time",workTime);
                responseObject.put("Address", country);

                for (int month = 1; month <= 12; month++) {
                    String monthName = getMonthName(month);
                    responseObject.put("Planned Hours for " + monthName, plannedHoursArray[month - 1]);
                }

                for (int month = 1; month <= 12; month++) {
                    String monthName = getMonthName(month);
                    responseObject.put("Available Hours for " + monthName, availableHoursArray[month - 1]);
                }

                for (int month = 1; month <= 12; month++) {
                    String monthName = getMonthName(month);
                    responseObject.put("Actual Hours for " + monthName, actualHoursArray[month - 1]);
                }
//                System.out.println(responseObject);
                return responseObject;

            } catch (JsonProcessingException e) {
                e.printStackTrace();
                return null;
            }
        }
    private long calculateWorkingDays(LocalDate startDate, LocalDate endDate) {
        // Use IntStream to iterate over the days and count the working days
        return IntStream.rangeClosed(0, endDate.getDayOfMonth() - 1)
                .mapToObj(startDate::plusDays)
                .filter(date -> date.getDayOfWeek() != DayOfWeek.SATURDAY && date.getDayOfWeek() != DayOfWeek.SUNDAY)
                .count();
    }

   
    public Object transformData(Object rawData, String apiKey) {
        ObjectMapper objectMapper = new ObjectMapper();
        RestTemplate restTemplate = new RestTemplate();

        try {
            JsonNode userJsonNode = objectMapper.readTree(objectMapper.writeValueAsString(rawData));
            JsonNode userArray = userJsonNode.get("data");

            List<ObjectNode> transformedData = new ArrayList<>();
            
            for (JsonNode userNode : userArray) {
                ObjectNode transformedObject = objectMapper.createObjectNode();

                // Existing transformation logic
                JsonNode roleNode = userNode.get("role");
                if (roleNode != null && roleNode.get("name") != null) {
                    transformedObject.put("Role", roleNode.get("name").asText());
                } else {
                    transformedObject.put("Role", "");
                }

                transformedObject.put("Resource Name", userNode.get("name").asText());
                transformedObject.put("Title", userNode.get("title").asText());

                double fte = userNode.get("fte").asDouble();
                transformedObject.put("FTE", fte);
                double workTime = userNode.get("workTime").asDouble();
                transformedObject.put("Work Time", workTime);
                String country = userNode.get("country").asText();
                transformedObject.put("Address", country);

                // Retrieve scheduleID for the user
                String userId = userNode.get("ID").asText();
                Object userData = getDashboardDataForUser(apiKey, userId);
                JsonNode userWithScheduleId = objectMapper.readTree(objectMapper.writeValueAsString(userData));

                JsonNode scheduleNode = userWithScheduleId.path("data").get(0).path("scheduleID");
                if (scheduleNode != null && scheduleNode.isTextual()) {
                    String scheduleId = scheduleNode.asText();
                    String scheduleApiUrl = "https://foolproof.my.workfront.com/attask/api/v17.0/sched/search?$$FIRST=0&$$LIMIT=2000&fields=*,nonWorkDays:*&ID=" + scheduleId + "&apiKey=" + apiKey;
                    String scheduleData = restTemplate.getForObject(scheduleApiUrl, String.class);

                    JsonNode scheduleIDNode = objectMapper.readTree(scheduleData);
                    JsonNode nonWorkDaysArray = scheduleIDNode.path("data").get(0).path("nonWorkDays");

                    List<String> nonWorkDatesList = StreamSupport.stream(nonWorkDaysArray.spliterator(), false)
                            .map(nonWorkDayNode -> nonWorkDayNode.path("nonWorkDate").asText())
                            .collect(Collectors.toList());

                    // Filter non-work dates for the current year and convert to array
                    int currentYear = LocalDate.now().getYear();
                    String[] nonWorkDatesArray2022ToCurrentYear = nonWorkDatesList.stream()
                            .filter(date -> date.startsWith(String.valueOf(currentYear)) ||
                                    date.startsWith(String.valueOf(currentYear - 1)) ||
                                    date.startsWith(String.valueOf(currentYear - 2)))
                            .toArray(String[]::new);

                    // Iterate over each week
                    List<LocalDate[]> weekDateRanges = getWeekDateRanges();
                    int[] nonWorkDaysCount = new int[weekDateRanges.size()];
                    int[] reservedDaysCount = new int[weekDateRanges.size()];
                    double[] availableTimeArray = new double[weekDateRanges.size()];
                    double[] plannedHoursArray = new double[weekDateRanges.size()];
                    double[] actualHoursArray = new double[weekDateRanges.size()];

                    // Iterate over each week date range
                    for (int weekIndex = 0; weekIndex < weekDateRanges.size(); weekIndex++) {
                        LocalDate[] dateRanges = weekDateRanges.get(weekIndex);

                        // Reset counts for each week
                        int nonWorkDaysCountForWeek = 0;
                        int reservedDaysCountForWeek = 0;

                        // Iterate over each day in the date range
                        for (LocalDate currentDate = dateRanges[0]; !currentDate.isAfter(dateRanges[1]); currentDate = currentDate.plusDays(1)) {
                            // Check if the current date is a weekday (Monday to Friday)
                            if (currentDate.getDayOfWeek().getValue() >= DayOfWeek.MONDAY.getValue() &&
                                    currentDate.getDayOfWeek().getValue() <= DayOfWeek.FRIDAY.getValue()) {
                                // Check if the current date is in the non-work dates array for the current year
                                if (Arrays.asList(nonWorkDatesArray2022ToCurrentYear).contains(currentDate.toString())) {
                                    nonWorkDaysCountForWeek++;
                                }
                            }
                        }

                        // Perform the third API call for Reserved Time logic
                        String reservedTimesApiUrl = "https://foolproof.my.workfront.com/attask/api/v17.0/user/search?$$FIRST=0&$$LIMIT=1000&fields=name,role,*,reservedTimes:*&ID=" + userId + "&apiKey=" + apiKey;

                        try {
                            String reservedTimesData = restTemplate.getForObject(reservedTimesApiUrl, String.class);
                            JsonNode reservedTimesNode = objectMapper.readTree(reservedTimesData).path("data").get(0).path("reservedTimes");

                            // DateTimeFormatter for parsing date strings
                            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss:SSSZ");

                            // Convert JsonNode to List<JsonNode>
                            List<JsonNode> reservedTimesList = StreamSupport.stream(reservedTimesNode.spliterator(), false)
                                    .collect(Collectors.toList());

                            // Iterate over each reserved time
                            for (JsonNode reservedTimeNode : reservedTimesList) {
                                String reservedStartDateStr = reservedTimeNode.path("startDate").asText();
                                String reservedEndDateStr = reservedTimeNode.path("endDate").asText();

                                try {
                                    LocalDateTime reservedStartDate = LocalDateTime.parse(reservedStartDateStr, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss:SSSZ"));
                                    LocalTime startTimeOfReservedTime = reservedStartDate.toLocalTime();
                                    LocalDateTime reservedEndDate = LocalDateTime.parse(reservedEndDateStr, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss:SSSZ"));

                                    // Check if the reservedTime intersects with the week's date range
                                    if (reservedStartDate.isBefore(dateRanges[1].atTime(LocalTime.MAX)) && reservedEndDate.isAfter(dateRanges[0].atStartOfDay())) {
                                        // Calculate the overlap start and end
                                        LocalDateTime overlapStart = reservedStartDate.isAfter(dateRanges[0].atTime(startTimeOfReservedTime)) ? reservedStartDate : dateRanges[0].atTime(startTimeOfReservedTime);
                                        LocalDateTime overlapEnd = reservedEndDate.isBefore(dateRanges[1].atTime(LocalTime.MAX)) ? reservedEndDate : dateRanges[1].atTime(LocalTime.MAX);

                                        // Calculate the duration of the overlap
                                        Duration overlapDuration = Duration.between(overlapStart, overlapEnd);

                                        // Calculate the number of days in the overlap, considering the time part
                                        long overlapDays = overlapDuration.toDaysPart() + (overlapDuration.toHoursPart() > 0 || overlapDuration.toMinutesPart() > 0 || overlapDuration.toSecondsPart() > 0 ? 1 : 0);

                                        // Increment the reservedDaysCountForWeek by the overlapDays
                                        reservedDaysCountForWeek += overlapDays;
                                    }
                                } catch (Exception e) {
                                    System.out.println("Error parsing date for Reserved Time: " + e.getMessage());
                                }
                            }

                            // Store the counts in the arrays
                            nonWorkDaysCount[weekIndex] = nonWorkDaysCountForWeek;
                            reservedDaysCount[weekIndex] = reservedDaysCountForWeek;

                            // Determine working hours per day based on the country
                            double workingHoursPerDay = (country.equalsIgnoreCase("UK")) ? 7.5 : 8.0;

                            // Calculate the available time for the week
                            double availableTimeForWeek = (5 - (nonWorkDaysCountForWeek + reservedDaysCountForWeek)) * workingHoursPerDay;

                            // Store the available time in the array
                            availableTimeArray[weekIndex] = availableTimeForWeek;

//                             Store planned hours for the week
                            plannedHoursArray[weekIndex] = getPlannedHoursForWeekWithRetry(userId, apiKey,dateRanges);

                            
                         // Store actual hours for the week
                            double actualHoursWeek = getPlannedOrActualHoursForWeek(userNode, apiKey, dateRanges, "actualWorkCompleted");
                            actualHoursArray[weekIndex] = actualHoursWeek / 60;

                        } catch (Exception e) {
                            e.printStackTrace();
                            return ResponseEntity.status(500).body("Error processing Reserved Time API response");
                        }
                    }

                    // Create a JSON object for weekly data
                    ObjectNode weeklyDataObject = objectMapper.createObjectNode();

                    // Iterate over each week
                    for (int weekIndex = 0; weekIndex < weekDateRanges.size(); weekIndex++) {
                        LocalDate[] dateRanges = weekDateRanges.get(weekIndex);

                        // Create a JSON object for each week
                        ObjectNode weekObject = objectMapper.createObjectNode();
                        weekObject.put("Planned Hours", plannedHoursArray[weekIndex]);
                        weekObject.put("Available Hours", availableTimeArray[weekIndex]);
                        weekObject.put("Actual Hours", actualHoursArray[weekIndex]);

                        // Add the week's data to the weekly object
                        weeklyDataObject.set(dateRanges[0].toString() + " - " + dateRanges[1].toString(), weekObject);
                    }

                    // Add the weekly data object to the transformed object
                    transformedObject.setAll(weeklyDataObject);
//                    System.out.println(transformedObject);
                } else {
                    transformedObject.put("Schedule ID", "N/A");
                }
                
                transformedData.add(transformedObject);
//                System.out.println(transformedObject);
            }
//            System.out.println(transformedData);
            ObjectNode resultObject = objectMapper.createObjectNode();
            resultObject.set("data", objectMapper.valueToTree(transformedData));

            return resultObject;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private List<LocalDate[]> getWeekDateRanges() {
        List<LocalDate[]> weekDateRanges = new ArrayList<>();

        // Start date for the iteration (2022-01-02)
        LocalDate startDate = LocalDate.of(2022, 1, 2);

        // End date for the iteration (current date)
        LocalDate endDate = LocalDate.now().plusWeeks(6);

        // Iterate until the end of the current week
        while (startDate.isBefore(endDate) || startDate.isEqual(endDate)) {
            // Ensure the start date is not a Sunday
            if (startDate.getDayOfWeek() == DayOfWeek.SUNDAY) {
                startDate = startDate.plusDays(1);
                continue;
            }

            // Calculate the end date of the week (excluding Saturdays)
            LocalDate endDateOfWeek = startDate.plusDays(4); // Friday

            weekDateRanges.add(new LocalDate[]{startDate, endDateOfWeek});

            // Move to the next week (start from the next Monday)
            startDate = endDateOfWeek.plusDays(3); // Move to the next Monday
        }

        return weekDateRanges;
    }
   
   private Object makeHttpRequestWithRetry(String url, String apiKey) {
	    int maxRetries = 100000; // Adjust the number of retries as needed
	    int retryDelayMillis = 3000; // 3 second delay, adjust as needed

	    for (int attempt = 1; attempt <= maxRetries; attempt++) {
	        try {
	            RestTemplate restTemplate = new RestTemplate();
	            return restTemplate.getForObject(url, Object.class);
	        } catch (RestClientResponseException | ResourceAccessException e) {
	            if (attempt < maxRetries) {
	                System.out.println("Retry attempt " + attempt + " after delay...");

	                try {
	                    Thread.sleep(retryDelayMillis);
	                } catch (InterruptedException ex) {
	                    ex.printStackTrace();
	                }
	            } else {
	                System.err.println("All retry attempts failed. Exception: " + e.getMessage());
	                throw e;
	            }
	        }
	    }

	    return null;
	}

   private double getPlannedHoursForWeekWithRetry(String userId, String apiKey, LocalDate[] dateRanges) {
	    int maxRetries = 100000; // Adjust the number of retries as needed
	    int retryDelayMillis = 3000; // 3 second delay, adjust as needed

	    for (int attempt = 1; attempt <= maxRetries; attempt++) {
	        try {
	            return getPlannedHoursForWeek(userId, apiKey,dateRanges);
	        } catch (RestClientResponseException | ResourceAccessException e) {
	            if (attempt < maxRetries) {
	                // Log or print a message indicating the retry
	                System.out.println("Retry attempt " + attempt + " after delay...");

	                // Sleep for a short delay before retrying
	                try {
	                    Thread.sleep(retryDelayMillis);
	                } catch (InterruptedException ex) {
	                    // Handle interruption if needed
	                    ex.printStackTrace();
	                }
	            } else {
	                // Log or handle the exception if all retries fail
	                System.err.println("All retry attempts failed. Exception: " + e.getMessage());
	                throw e; // Or rethrow the exception if you want to propagate it after retries
	            }
	        }
	    }

	    return 0.0; // You should not reach this point normally
	}

	private double getPlannedHoursForWeek(String userId, String apiKey,  LocalDate[] dateRanges) {
	    RestTemplate restTemplate = new RestTemplate();
	    ObjectMapper objectMapper = new ObjectMapper();
//	    LocalDate[] dateRanges = getLast6WeeksDateRanges(weekNumber);

	    try {
	        String taskApiUrl = "https://foolproof.my.workfront.com/attask/api/v17.0/task/search" +
	                "?plannedStartDate_Range=" + dateRanges[1] +
	                "&plannedStartDate_Mod=between" +
	                "&plannedStartDate=" + dateRanges[0] +
	                "&fields=assignments:*" +
	                "&$$FIRST=0&$$LIMIT=2000&apiKey=" + apiKey;

	        String taskData = restTemplate.getForObject(taskApiUrl, String.class);
	        JsonNode taskJsonNode = objectMapper.readTree(taskData).path("data");

	        double totalPlannedHours = 0;

	        for (JsonNode taskNode : taskJsonNode) {
	            JsonNode assignmentsNode = taskNode.path("assignments");

	            for (JsonNode assignmentNode : assignmentsNode) {
	                String assignedToId = assignmentNode.path("assignedToID").asText();

	                if (userId.equals(assignedToId)) {
	                    double work = assignmentNode.path("work").asDouble();
	                    totalPlannedHours += work;
	                }
	            }
	        }

	        return totalPlannedHours;
	    } catch (Exception e) {
	        e.printStackTrace();
	        // Handle exceptions appropriately (log, return default value, etc.)
	        return 0.0;
	    }
	}
	
	private double getReportedHoursLast6Weeks(String userId, String apiKey,LocalDate[] dateRanges) {
	    try {
	        // Calculate the start and end dates for the last 6 weeks
//	        LocalDate[] dateRanges = getLast6WeeksDateRanges(1);
	        ObjectMapper objectMapper=new ObjectMapper();
	        // Build the URL for the tshet/search API call
	        String tshetSearchUrl = "https://foolproof.my.workfront.com/attask/api/v17.0/tshet/search" +
	                "?$$FIRST=0&$$LIMIT=1000&fields=*" +
	                "&startDate=" + dateRanges[0] +
	                "&endDate_Mod=between&endDate_Range=" + dateRanges[1] +
	                "&userID=" + userId +
	                "&apiKey=" + apiKey;

	        // Make the API call using the retry mechanism
	        Object tshetData = makeHttpRequestWithRetry(tshetSearchUrl, apiKey);

	        if (tshetData != null) {
	            // Extract the reported hours from the API response
	            JsonNode tshetJsonNode = objectMapper.readTree(objectMapper.writeValueAsString(tshetData));
	            JsonNode tshetArray = tshetJsonNode.get("data");
	            
	            if (tshetArray.size() > 0) {
	                JsonNode totalHoursNode = tshetArray.get(0).path("totalHours");
	                return totalHoursNode.isMissingNode() ? 0.0 : totalHoursNode.asDouble();
	            }
	        }
	    } catch (Exception e) {
	        e.printStackTrace();
	    }

	    return 0.0;
	}
   public double[] getPlannedHoursForWeek(RestTemplate restTemplate, ObjectMapper objectMapper, String apiKey, String userId, int weekNumber, LocalDate[] dateRanges) {
       int maxRetries = 3;
       int retryCount = 0;

       do {
           try {
               String taskApiUrl = "https://foolproof.my.workfront.com/attask/api/v17.0/task/search" +
                       "?plannedStartDate_Range=" + dateRanges[1] +
                       "&plannedStartDate_Mod=between" +
                       "&plannedStartDate=" + dateRanges[0] +
                       "&fields=assignments:*" +
                       "&$$FIRST=0&$$LIMIT=2000&apiKey=" + apiKey;

               String taskData = restTemplate.getForObject(taskApiUrl, String.class);
               JsonNode taskJsonNode = objectMapper.readTree(taskData).path("data");

               double totalPlannedHours = 0;

               for (JsonNode taskNode : taskJsonNode) {
                   JsonNode assignmentsNode = taskNode.path("assignments");

                   for (JsonNode assignmentNode : assignmentsNode) {
                       String assignedToId = assignmentNode.path("assignedToID").asText();

                       if (userId.equals(assignedToId)) {
                           double work = assignmentNode.path("work").asDouble();
                           totalPlannedHours += work;
                       }
                   }
               }

               double[] plannedHoursArray = new double[6];
               plannedHoursArray[weekNumber - 1] = totalPlannedHours;

               return plannedHoursArray;
           } catch (ResourceAccessException e) {
               // Handle the exception (log, retry, etc.)
               e.printStackTrace();
               retryCount++;
           } catch (Exception e) {
               e.printStackTrace();
               retryCount++;
           }
       } while (retryCount < maxRetries);

       // If all retries fail, return default values or mark this week's data as not available
       return new double[]{0.0, 0.0, 0.0, 0.0, 0.0, 0.0};
   }     
   private double getPlannedOrActualHoursForWeek(JsonNode userNode, String apiKey, LocalDate[] dateRanges, String field) {
	    String assignedToID = userNode.get("ID").asText();

	    String assignmentApiUrl = "https://foolproof.my.workfront.com/attask/api/v17.0/assgn/search?fields=" + field +
	            "&assignedToID=" + assignedToID +
	            "&actualWorkPerDayStartDate_Mod=between" +
	            "&actualWorkPerDayStartDate_Range=" + dateRanges[1] + "&actualWorkPerDayStartDate=" + dateRanges[0] +
	            "&apiKey=" + apiKey;

	    return getTotalHoursForAssignment(assignmentApiUrl, field, apiKey);
	}


    private double getTotalHoursForAssignment(String assignmentApiUrl, String field, String apiKey) {
        
        Object assignmentData = makeHttpRequestWithRetry(assignmentApiUrl, apiKey);

        double totalHours = 0;

        if (assignmentData != null) {
            ObjectMapper objectMapper = new ObjectMapper();
            try {
                JsonNode assignmentJsonNode = objectMapper.readTree(objectMapper.writeValueAsString(assignmentData));
                JsonNode assignmentArray = assignmentJsonNode.get("data");

                for (JsonNode assignmentNode : assignmentArray) {
                    totalHours += assignmentNode.get(field).asDouble();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        return totalHours;
    }

    
    private LocalDate[] getLast6WeeksDateRanges(int weekNumber) {
        LocalDate currentDate = LocalDate.now();
        // Adjust the current date to the most recent Monday
        LocalDate mondayDate = currentDate.with(TemporalAdjusters.previous(DayOfWeek.MONDAY));
        // Calculate the start date by subtracting weeks
        LocalDate startDate = mondayDate.minusWeeks(weekNumber - 1);
        // Calculate the end date as the previous Friday
        LocalDate endDate = startDate.plusDays(4);
        return new LocalDate[]{startDate, endDate};
    }
    
    private LocalDate[] getMonthDateRange(int year, int month) {
        LocalDate firstDayOfMonth = LocalDate.of(year, month, 1);
        LocalDate lastDayOfMonth = LocalDate.of(year, month, firstDayOfMonth.lengthOfMonth());
        return new LocalDate[]{firstDayOfMonth, lastDayOfMonth};
    }
    private double getActualHoursForMonth(JsonNode userNode, String apiKey, int historicYear, int historicMonthIndex) {
        String assignedToID = userNode.get("ID").asText();
        LocalDate[] dateRange = getMonthDateRange(historicYear, historicMonthIndex + 1);
        String assignmentApiUrl = "https://foolproof.my.workfront.com/attask/api/v17.0/assgn/search?fields=actualWorkCompleted" +
                "&assignedToID=" + assignedToID +
                "&actualWorkPerDayStartDate_Mod=between" +
                "&actualWorkPerDayStartDate_Range=" + dateRange[0] + "&actualWorkPerDayStartDate=" + dateRange[1] +
                "&apiKey=" + apiKey;

        return getTotalHoursForAssignment(assignmentApiUrl, "actualWorkCompleted",apiKey);
    }

    private String getMonthName(int month) {
        return switch (month) {
            case 1 -> "January";
            case 2 -> "February";
            case 3 -> "March";
            case 4 -> "April";
            case 5 -> "May";
            case 6 -> "June";
            case 7 -> "July";
            case 8 -> "August";
            case 9 -> "September";
            case 10 -> "October";
            case 11 -> "November";
            case 12 -> "December";
            default -> throw new IllegalArgumentException("Invalid month: " + month);
        };
    }


}
