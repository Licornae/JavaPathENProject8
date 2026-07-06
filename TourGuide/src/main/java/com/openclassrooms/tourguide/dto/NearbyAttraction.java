package com.openclassrooms.tourguide.dto;

public record NearbyAttraction(
		String attractionName,
		double attractionLatitude,
		double attractionLongitude,
		double userLatitude,
		double userLongitude,
		double distanceInMiles,
		int rewardPoints) {
}
