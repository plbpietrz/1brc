/*
 *  Copyright 2023 The original authors
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package dev.morling.onebrc;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class CalculateAverage_plbpietrz {

    private static final String FILE = "./measurements.txt";
    private static final int READ_SIZE = 1024;
    private static final int CPU_COUNT = Runtime.getRuntime().availableProcessors();
    private static final Map<Integer, String> STATION_NAMES = new ConcurrentHashMap<>(512);

    private static class TemperatureStats {
        double min = 999, max = -999d;
        double accumulated;
        int count;
    }

    private record FilePart(long pos, long size) {
    }

    public static void main(String[] args) throws IOException {
        Path inputFilePath = Path.of(FILE);
        Map<Integer, TemperatureStats> results;// = new HashMap<>();
        try (RandomAccessFile inputFile = new RandomAccessFile(inputFilePath.toFile(), "r")) {
            var parsedBuffers = partitionInput(inputFile)
                    .stream()
                    .parallel()
                    .map(fp -> getMappedByteBuffer(fp, inputFile))
                    .map(CalculateAverage_plbpietrz::parseBuffer);
            results = parsedBuffers.flatMap(m -> m.entrySet().stream())
                    .collect(
                            Collectors.groupingBy(
                                    Map.Entry::getKey,
                                    Collectors.reducing(
                                            new TemperatureStats(),
                                            Map.Entry::getValue,
                                            CalculateAverage_plbpietrz::mergeTemperatureStats)));
            try (PrintWriter pw = new PrintWriter(new BufferedOutputStream(System.out))) {
                formatResults(pw, results);
            }
        }
    }

    private static List<FilePart> partitionInput(RandomAccessFile inputFile) throws IOException {
        List<FilePart> fileParts = new ArrayList<>();
        long fileLength = inputFile.length();

        long blockSize = Math.min(fileLength, Math.max(1024, fileLength / CPU_COUNT));

        for (long start = 0, end; start < fileLength; start = end) {
            end = findMinBlockOffset(inputFile, start, blockSize);
            fileParts.add(new FilePart(start, end - start));
        }
        return fileParts;
    }

    private static long findMinBlockOffset(RandomAccessFile file, long startPosition, long minBlockSize) throws IOException {
        long length = file.length();
        if (startPosition + minBlockSize < length) {
            file.seek(startPosition + minBlockSize);
            while (file.readByte() != '\n') {
            }
            return file.getFilePointer();
        }
        else {
            return length;
        }
    }

    private static MappedByteBuffer getMappedByteBuffer(FilePart fp, RandomAccessFile inputFile) {
        try {
            return inputFile.getChannel().map(FileChannel.MapMode.READ_ONLY, fp.pos, fp.size);
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Map<Integer, TemperatureStats> parseBuffer(MappedByteBuffer buffer) {
        byte[] readLong = new byte[READ_SIZE];
        byte[] stationName = new byte[512];
        byte[] temperature = new byte[32];
        int stationLineNameLenght = 0;
        int temperatureLineLenght = 0;
        int stationNameHash = 0;

        int limit = buffer.limit();
        boolean readingName = true;
        Map<Integer, TemperatureStats> temperatures = new HashMap<>();
        Map<Integer, String> stationNames = new HashMap<>();

        int bytesToRead = Math.min(READ_SIZE, limit - buffer.position());
        while (bytesToRead > 0) {
            if (bytesToRead == READ_SIZE) {
                buffer.get(readLong);
            }
            else {
                for (int j = 0; j < bytesToRead; ++j)
                    readLong[j] = buffer.get();
            }

            for (int i = 0; i < bytesToRead; ++i) {
                byte aChar = readLong[i];
                if (readingName) {
                    if (aChar != ';') {
                        if (aChar != '\n') {
                            stationName[stationLineNameLenght++] = aChar;
                            stationNameHash = stationNameHash * 31 + aChar;
                        }
                    }
                    else {
                        readingName = false;
                    }
                }
                else {
                    if (aChar != '\n') {
                        temperature[temperatureLineLenght++] = aChar;
                    }
                    else {
                        int len = stationLineNameLenght;
                        Integer pos = stationNameHash;

                        double temp = parseTemperature(temperature, temperatureLineLenght);
                        TemperatureStats weatherStats = temperatures.computeIfAbsent(pos, _ignored_ -> new TemperatureStats());

                        weatherStats.min = Math.min(weatherStats.min, temp);
                        weatherStats.max = Math.max(weatherStats.max, temp);
                        weatherStats.accumulated += temp;
                        weatherStats.count++;

                        stationLineNameLenght = 0;
                        temperatureLineLenght = 0;
                        stationNameHash = 0;
                        readingName = true;

                        if (!stationNames.containsKey(pos))
                            stationNames.put(pos, new String(stationName, 0, len, Charset.defaultCharset()));
                    }
                }
            }

            bytesToRead = Math.min(READ_SIZE, limit - buffer.position());
        }
        STATION_NAMES.putAll(stationNames);
        return temperatures;
    }

    private static double parseTemperature(byte[] temperature, int temperatureSize) {
        double sign = 1;
        double manitssa = 0;
        double exponent = 1;
        for (int i = 0; i < temperatureSize; ++i) {
            byte c = temperature[i];
            switch (c) {
                case '-':
                    sign = -1;
                    break;
                case '.':
                    for (int j = i; j < temperatureSize - 1; ++j)
                        exponent *= 0.1;
                    break;
                default:
                    manitssa = manitssa * 10 + (c - 48);
            }
        }
        return sign * manitssa * exponent;
    }

    private static TemperatureStats mergeTemperatureStats(TemperatureStats v1, TemperatureStats v2) {
        TemperatureStats acc = new TemperatureStats();
        acc.min = Math.min(v1.min, v2.min);
        acc.max = Math.max(v1.max, v2.max);
        acc.accumulated = v1.accumulated + v2.accumulated;
        acc.count = v1.count + v2.count;
        return acc;
    }

    private static void formatResults(PrintWriter pw, Map<Integer, TemperatureStats> resultsMap) {
        pw.print('{');
        var results = new ArrayList<>(resultsMap.entrySet());
        results.sort(Comparator.comparing(e -> STATION_NAMES.get(e.getKey())));
        var iterator = results.iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            TemperatureStats stats = entry.getValue();
            pw.printf("%s=%.1f/%.1f/%.1f",
                    STATION_NAMES.get(entry.getKey()),
                    stats.min,
                    stats.accumulated / stats.count,
                    stats.max);
            if ((iterator.hasNext()))
                pw.print(", ");
        }
        pw.println('}');
    }

}
