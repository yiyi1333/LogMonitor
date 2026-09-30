package com.logmonitor.service;
import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
class SqlChunkTest {
    @Test void boundsRowsAndUtf8ParameterBytesWithoutSplittingTheCallerTransaction() {
        List<Integer> sizes=new ArrayList<>();List<Integer> rows=new ArrayList<>();
        LogStorageService.chunks(Collections.nCopies(1200,2500),Integer::intValue,chunk->{sizes.add(chunk.stream().mapToInt(Integer::intValue).sum());rows.add(chunk.size());});
        assertThat(sizes).allMatch(n->n<=1024*1024);assertThat(rows).allMatch(n->n<=500);
        assertThat(rows.stream().mapToInt(Integer::intValue).sum()).isEqualTo(1200);
        assertThatThrownBy(()->LogStorageService.chunks(List.of(1024*1024+1),Integer::intValue,chunk->{})).isInstanceOf(IllegalArgumentException.class);
    }
}
