package com.aiminilab.aitoolmarket.ppt.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ppt")
public class PptEngineProperties {

    private Engine engine = new Engine();
    private Billing billing = new Billing();

    public Engine getEngine() {
        return engine;
    }

    public void setEngine(Engine engine) {
        this.engine = engine == null ? new Engine() : engine;
    }

    public Billing getBilling() {
        return billing;
    }

    public void setBilling(Billing billing) {
        this.billing = billing == null ? new Billing() : billing;
    }

    public static class Engine {
        private String baseUrl = "http://127.0.0.1:5000";
        private int connectTimeoutMs = 5000;
        private int readTimeoutMs = 300_000;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public int getConnectTimeoutMs() {
            return connectTimeoutMs;
        }

        public void setConnectTimeoutMs(int connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
        }

        public int getReadTimeoutMs() {
            return readTimeoutMs;
        }

        public void setReadTimeoutMs(int readTimeoutMs) {
            this.readTimeoutMs = readTimeoutMs;
        }
    }

    public static class Billing {
        private int createProjectCredits = 5;
        private int generateOutlineCredits = 10;
        private int generateDescriptionsCredits = 20;
        private int generateImagesCredits = 50;
        private int exportPptxCredits = 5;
        private int exportPdfCredits = 5;

        public int getCreateProjectCredits() {
            return createProjectCredits;
        }

        public void setCreateProjectCredits(int createProjectCredits) {
            this.createProjectCredits = createProjectCredits;
        }

        public int getGenerateOutlineCredits() {
            return generateOutlineCredits;
        }

        public void setGenerateOutlineCredits(int generateOutlineCredits) {
            this.generateOutlineCredits = generateOutlineCredits;
        }

        public int getGenerateDescriptionsCredits() {
            return generateDescriptionsCredits;
        }

        public void setGenerateDescriptionsCredits(int generateDescriptionsCredits) {
            this.generateDescriptionsCredits = generateDescriptionsCredits;
        }

        public int getGenerateImagesCredits() {
            return generateImagesCredits;
        }

        public void setGenerateImagesCredits(int generateImagesCredits) {
            this.generateImagesCredits = generateImagesCredits;
        }

        public int getExportPptxCredits() {
            return exportPptxCredits;
        }

        public void setExportPptxCredits(int exportPptxCredits) {
            this.exportPptxCredits = exportPptxCredits;
        }

        public int getExportPdfCredits() {
            return exportPdfCredits;
        }

        public void setExportPdfCredits(int exportPdfCredits) {
            this.exportPdfCredits = exportPdfCredits;
        }
    }
}
