package com.aiminilab.aitoolmarket.ppt;

import java.util.regex.Pattern;

public final class PptConstants {

    public static final String TOOL_CODE = "banana_ppt_generator";
    public static final String INTEGRATION_MODE = "PPT_WORKSPACE";
    public static final Pattern WORKFLOW_PATTERN = Pattern.compile("<!--\\s*ppt-workflow:(\\{.*?})\\s*-->", Pattern.DOTALL);

    private PptConstants() {
    }
}
