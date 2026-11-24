package cn.qingliao.common.model.dto;

import com.baomidou.mybatisplus.core.metadata.IPage;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PageResponse<T> implements Serializable {
    private static final long serialVersionUID = 1L;
    private List<T> list;
    private Long total;
    private Long pageSize;
    private Long pageNum;
    private Long pages;
    private Boolean hasNext;
    private Boolean hasPrevious;

    public static <T> PageResponse<T> of(IPage<T> page) {
        return PageResponse.<T>builder()
                .list(page.getRecords())
                .total(page.getTotal())
                .pageSize(page.getSize())
                .pageNum(page.getCurrent())
                .pages(page.getPages())
                .hasNext(page.getCurrent() < page.getPages())
                .hasPrevious(page.getCurrent() > 1)
                .build();
    }

    public static <T, R> PageResponse<R> of(IPage<T> page, Function<T, R> converter) {
        List<R> convertedList =
                page.getRecords().stream().map(converter).collect(Collectors.toList());
        return PageResponse.<R>builder()
                .list(convertedList)
                .total(page.getTotal())
                .pageSize(page.getSize())
                .pageNum(page.getCurrent())
                .pages(page.getPages())
                .hasNext(page.getCurrent() < page.getPages())
                .hasPrevious(page.getCurrent() > 1)
                .build();
    }

    public static <T> PageResponse<T> of(List<T> allData, int pageNum, int pageSize) {
        if (allData == null || allData.isEmpty()) {
            return PageResponse.<T>builder()
                    .list(Collections.emptyList())
                    .total(0L)
                    .pageSize((long) pageSize)
                    .pageNum((long) pageNum)
                    .pages(0L)
                    .hasNext(false)
                    .hasPrevious(false)
                    .build();
        }
        long total = allData.size();
        long pages = (total + pageSize - 1) / pageSize;
        int fromIndex = (pageNum - 1) * pageSize;
        int toIndex = Math.min(fromIndex + pageSize, allData.size());
        List<T> list =
                fromIndex < allData.size()
                        ? allData.subList(fromIndex, toIndex)
                        : Collections.emptyList();
        return PageResponse.<T>builder()
                .list(list)
                .total(total)
                .pageSize((long) pageSize)
                .pageNum((long) pageNum)
                .pages(pages)
                .hasNext(pageNum < pages)
                .hasPrevious(pageNum > 1)
                .build();
    }

    public static <T> PageResponse<T> empty(int pageNum, int pageSize) {
        return PageResponse.<T>builder()
                .list(Collections.emptyList())
                .total(0L)
                .pageSize((long) pageSize)
                .pageNum((long) pageNum)
                .pages(0L)
                .hasNext(false)
                .hasPrevious(false)
                .build();
    }
}
