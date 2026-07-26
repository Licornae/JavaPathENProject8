package com.openclassrooms.tourguide.service;

import com.openclassrooms.tourguide.helper.InternalTestHelper;
import com.openclassrooms.tourguide.tracker.Tracker;
import com.openclassrooms.tourguide.user.User;
import com.openclassrooms.tourguide.user.UserReward;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.openclassrooms.tourguide.dto.NearbyAttraction;

import gpsUtil.GpsUtil;
import gpsUtil.location.Attraction;
import gpsUtil.location.Location;
import gpsUtil.location.VisitedLocation;

import tripPricer.Provider;
import tripPricer.TripPricer;

@Service
public class TourGuideService {

	private Logger logger = LoggerFactory.getLogger(TourGuideService.class);
	private final GpsUtil gpsUtil;
	private final RewardsService rewardsService;
	private final TripPricer tripPricer = new TripPricer();
	public final Tracker tracker;
	boolean testMode = true;

	private static final int TRACK_LOCATION_THREAD_POOL_SIZE = 100;
	private final ExecutorService trackingExecutor =
			Executors.newFixedThreadPool(TRACK_LOCATION_THREAD_POOL_SIZE);

	public TourGuideService(GpsUtil gpsUtil, RewardsService rewardsService) {
		this.gpsUtil = gpsUtil;
		this.rewardsService = rewardsService;
		
		Locale.setDefault(Locale.US);

		if (testMode) {
			logger.info("TestMode enabled");
			logger.debug("Initializing users");
			initializeInternalUsers();
			logger.debug("Finished initializing users");
		}
		tracker = new Tracker(this);
	}

	@PostConstruct
	private void startTracker() {
		tracker.startTracking();
	}

	public List<UserReward> getUserRewards(User user) {
		return user.getUserRewards();
	}

	public VisitedLocation getUserLocation(User user) {
        return (!user.getVisitedLocations().isEmpty()) ? user.getLastVisitedLocation()
                : trackUserLocation(user);
	}

	public User getUser(String userName) {
		return internalUserMap.get(userName);
	}

	public List<User> getAllUsers() {
		return new ArrayList<>(internalUserMap.values());
	}

	public void addUser(User user) {
		if (!internalUserMap.containsKey(user.getUserName())) {
			internalUserMap.put(user.getUserName(), user);
		}
	}

	public List<Provider> getTripDeals(User user) {
		int cumulativeRewardPoints = user.getUserRewards().stream().mapToInt(UserReward::getRewardPoints).sum();
		List<Provider> providers = tripPricer.getPrice(tripPricerApiKey, user.getUserId(),
				user.getUserPreferences().getNumberOfAdults(), user.getUserPreferences().getNumberOfChildren(),
				user.getUserPreferences().getTripDuration(), cumulativeRewardPoints);
		user.setTripDeals(providers);
		return providers;
	}

	public VisitedLocation trackUserLocation(User user) {
		VisitedLocation visitedLocation = fetchAndStoreUserLocation(user);
		rewardsService.calculateRewards(user);
		return visitedLocation;
	}

	public void trackUsersLocationAsync(List<User> users) {

		List<CompletableFuture<VisitedLocation>> locationFutures = users.stream()
				.map(user -> CompletableFuture.supplyAsync(() -> fetchAndStoreUserLocation(user), trackingExecutor))
				.toList();

		CompletableFuture.allOf(locationFutures.toArray(new CompletableFuture[0])).join();

		List<CompletableFuture<Void>> rewardsFutures = users.stream()
				.map(user -> CompletableFuture.runAsync(() -> rewardsService.calculateRewards(user), trackingExecutor))
				.toList();

		CompletableFuture.allOf(rewardsFutures.toArray(new CompletableFuture[0])).join();
    }

	public VisitedLocation fetchAndStoreUserLocation(User user) {
		VisitedLocation visitedLocation = gpsUtil.getUserLocation(user.getUserId());
		user.addToVisitedLocations(visitedLocation);
		return visitedLocation;
	}

	public List<Attraction> getNearByAttractions(VisitedLocation visitedLocation) {
		return gpsUtil.getAttractions().stream()
				.sorted(Comparator.comparingDouble(
						attraction -> rewardsService.getDistance(attraction, visitedLocation.location)))
				.limit(5)
				.collect(Collectors.toList());
	}

	public List<NearbyAttraction> getNearbyAttractionRecommendations(User user, VisitedLocation visitedLocation) {
		Location userLocation = visitedLocation.location;
		return getNearByAttractions(visitedLocation).stream()
				.map(attraction -> new NearbyAttraction(
						attraction.attractionName,
						attraction.latitude,
						attraction.longitude,
						userLocation.latitude,
						userLocation.longitude,
						rewardsService.getDistance(attraction, userLocation),
						rewardsService.getRewardPoints(attraction, user)))
				.collect(Collectors.toList());
	}

	@PreDestroy
	private void shutdownServices() {
		tracker.stopTracking();
		trackingExecutor.shutdown();

		try {
			if (!trackingExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
				trackingExecutor.shutdownNow();
			}
		} catch (InterruptedException e) {
			trackingExecutor.shutdownNow();
			Thread.currentThread().interrupt();
		}
	}

	/**********************************************************************************
	 * 
	 * Methods Below: For Internal Testing
	 * 
	 **********************************************************************************/
	private static final String tripPricerApiKey = "test-server-api-key";
	// Database connection will be used for external users, but for testing purposes
	// internal users are provided and stored in memory
	private final Map<String, User> internalUserMap = new HashMap<>();

	private void initializeInternalUsers() {
		IntStream.range(0, InternalTestHelper.getInternalUserNumber()).forEach(i -> {
			String userName = "internalUser" + i;
			String phone = "000";
			String email = userName + "@tourGuide.com";
			User user = new User(UUID.randomUUID(), userName, phone, email);
			generateUserLocationHistory(user);

			internalUserMap.put(userName, user);
		});
        logger.debug("Created {} internal test users.", InternalTestHelper.getInternalUserNumber());
	}

	private void generateUserLocationHistory(User user) {
		IntStream.range(0, 3).forEach(i -> {
			user.addToVisitedLocations(
					new VisitedLocation(user.getUserId(),
							new Location(generateRandomLatitude(),
							generateRandomLongitude()),
							getRandomTime()
					)
			);
		});
	}

	private double generateRandomLongitude() {
		double leftLimit = -180;
		double rightLimit = 180;
		return leftLimit + new Random().nextDouble() * (rightLimit - leftLimit);
	}

	private double generateRandomLatitude() {
		double leftLimit = -85.05112878;
		double rightLimit = 85.05112878;
		return leftLimit + new Random().nextDouble() * (rightLimit - leftLimit);
	}

	private Date getRandomTime() {
		LocalDateTime localDateTime = LocalDateTime.now().minusDays(new Random().nextInt(30));
		return Date.from(localDateTime.toInstant(ZoneOffset.UTC));
	}

}
