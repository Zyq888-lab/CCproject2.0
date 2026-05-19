package com.jifeng.assessment.common;

import lombok.Data;

@Data
public class PageQuery {
    private int page = 1;
    private int size = 20;
    private String sortBy;
    private String keyword;
}
