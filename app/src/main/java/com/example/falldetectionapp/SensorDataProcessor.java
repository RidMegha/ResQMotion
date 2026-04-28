package com.example.falldetectionapp;



import java.util.List;

public class SensorDataProcessor {

    public static float calculateMagnitude(float x, float y, float z) {
        return (float) Math.sqrt(x * x + y * y + z * z);
    }

    public static float[] normalizeData(List<Float> data) {
        float[] normalized = new float[data.size()];
        float mean = calculateMean(data);
        float std = calculateStd(data, mean);

        for (int i = 0; i < data.size(); i++) {
            normalized[i] = (data.get(i) - mean) / std;
        }

        return normalized;
    }

    private static float calculateMean(List<Float> data) {
        float sum = 0;
        for (float value : data) {
            sum += value;
        }
        return sum / data.size();
    }

    private static float calculateStd(List<Float> data, float mean) {
        float sum = 0;
        for (float value : data) {
            sum += Math.pow(value - mean, 2);
        }
        return (float) Math.sqrt(sum / data.size());
    }
}