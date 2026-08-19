package com.openclassrooms.tourguide.service;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import gpsUtil.GpsUtil;
import gpsUtil.location.Attraction;
import gpsUtil.location.Location;
import gpsUtil.location.VisitedLocation;
import rewardCentral.RewardCentral;
import com.openclassrooms.tourguide.user.User;
import com.openclassrooms.tourguide.user.UserReward;

@Service
public class RewardsService {
    private static final double STATUTE_MILES_PER_NAUTICAL_MILE = 1.15077945;

	// proximity in miles
    private int defaultProximityBuffer = 10;
	private int proximityBuffer = defaultProximityBuffer;
	private int attractionProximityRange = 200;
	private final GpsUtil gpsUtil;
	private final RewardCentral rewardCentral;
	private static final int REWARDS_THREAD_POOL_SIZE = 100;
	private final ExecutorService rewardsExecutor =
			Executors.newFixedThreadPool(REWARDS_THREAD_POOL_SIZE);
	
	public RewardsService(GpsUtil gpsUtil, RewardCentral rewardCentral) {
		this.gpsUtil = gpsUtil;
		this.rewardCentral = rewardCentral;
	}
	
	public void setProximityBuffer(int proximityBuffer) {
		this.proximityBuffer = proximityBuffer;
	}
	
	public void setDefaultProximityBuffer() {
		proximityBuffer = defaultProximityBuffer;
	}

	public CompletableFuture<Void> calculateRewardsAsync(List<User> users) {
		List<Attraction> attractions = gpsUtil.getAttractions();

		List<CompletableFuture<Void>> calculFutures = users.stream()
				.map(user -> CompletableFuture.runAsync(() -> calculateRewards(user, attractions),rewardsExecutor))
				.toList();

		return CompletableFuture.allOf(calculFutures.toArray(new CompletableFuture[0]));
	}

	public void calculateRewards(User user) {
		calculateRewards(user, gpsUtil.getAttractions());
	}

	private void calculateRewards(User user, List<Attraction> attractions) {

		Set<String> rewardedAttractions = user.getUserRewards().stream()
				.map(r -> r.attraction.attractionName)
				.collect(Collectors.toSet());

		for (VisitedLocation visitedLocation : user.getVisitedLocations()) {
			for (Attraction attraction : attractions) {
				if (!rewardedAttractions.contains(attraction.attractionName)
						&& nearAttraction(visitedLocation, attraction)) {
					user.addUserReward(new UserReward(
							visitedLocation,
							attraction,
							getRewardPoints(attraction, user)
					));
					rewardedAttractions.add(attraction.attractionName);
				}
			}
		}
	}

	public boolean isWithinAttractionProximity(Attraction attraction, Location location) {
		return !(getDistance(attraction, location) > attractionProximityRange);
	}
	
	private boolean nearAttraction(VisitedLocation visitedLocation, Attraction attraction) {
		return !(getDistance(attraction, visitedLocation.location) > proximityBuffer);
	}
	
	public int getRewardPoints(Attraction attraction, User user) {
		return rewardCentral.getAttractionRewardPoints(attraction.attractionId, user.getUserId());
	}
	
	public double getDistance(Location loc1, Location loc2) {
        double lat1 = Math.toRadians(loc1.latitude);
        double lon1 = Math.toRadians(loc1.longitude);
        double lat2 = Math.toRadians(loc2.latitude);
        double lon2 = Math.toRadians(loc2.longitude);

        double angle = Math.acos(Math.sin(lat1) * Math.sin(lat2)
                               + Math.cos(lat1) * Math.cos(lat2) * Math.cos(lon1 - lon2));

        double nauticalMiles = 60 * Math.toDegrees(angle);
        return STATUTE_MILES_PER_NAUTICAL_MILE * nauticalMiles;
	}

	@PreDestroy
	public void shutdown() {
		rewardsExecutor.shutdown();

		try {
			if (!rewardsExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
				rewardsExecutor.shutdownNow();
			}
		} catch (InterruptedException exception) {
			rewardsExecutor.shutdownNow();
			Thread.currentThread().interrupt();
		}
	}

}
