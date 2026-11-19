package com.jar.flowcapture.codegen;

import lombok.Getter;

/**
 * The feature areas that actually exist in Jar_Frontend_Automation today (confirmed:
 * src/main/Java/locaters/{goldLocators,upiLocators,webLocators},
 * pageObjectModel/{gold,upi,web}, src/test/java/com/jar/testCases/{goldTestCase,upiTestCases,webTestCase})
 * — offered as a dropdown in the dashboard instead of QA having to type raw package
 * paths for every new flow.
 */
@Getter
public enum TargetArea {
    GOLD("Gold", "locaters.goldLocators", "locaters/goldLocators", "pageObjectModel/gold", "pageObjectModel.gold",
            "com/jar/testCases/goldTestCase", "com.jar.testCases.goldTestCase"),
    UPI("UPI", "locaters.upiLocators", "locaters/upiLocators", "pageObjectModel/upi", "pageObjectModel.upi",
            "com/jar/testCases/upiTestCases", "com.jar.testCases.upiTestCases"),
    WEB("Web", "locaters.webLocators", "locaters/webLocators", "pageObjectModel/web", "pageObjectModel.web",
            "com/jar/testCases/webTestCase", "com.jar.testCases.webTestCase");

    private final String displayName;
    private final String locatorsPackage;
    private final String locatorsRelativePath;
    private final String pageRelativePath;
    private final String pagePackage;
    private final String testRelativePath;
    private final String testPackage;

    TargetArea(String displayName, String locatorsPackage, String locatorsRelativePath,
               String pageRelativePath, String pagePackage, String testRelativePath, String testPackage) {
        this.displayName = displayName;
        this.locatorsPackage = locatorsPackage;
        this.locatorsRelativePath = locatorsRelativePath;
        this.pageRelativePath = pageRelativePath;
        this.pagePackage = pagePackage;
        this.testRelativePath = testRelativePath;
        this.testPackage = testPackage;
    }
}
