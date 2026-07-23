package com.aiminilab.aitoolmarket.community.dto;

import com.aiminilab.aitoolmarket.community.entity.CommunityPost;

public class CommunityPostDiscoverRow extends CommunityPost {
    private String authorPublicCode;
    private String authorNickname;
    private String authorAvatarUrl;

    public String getAuthorPublicCode() {
        return authorPublicCode;
    }

    public void setAuthorPublicCode(String authorPublicCode) {
        this.authorPublicCode = authorPublicCode;
    }

    public String getAuthorNickname() {
        return authorNickname;
    }

    public void setAuthorNickname(String authorNickname) {
        this.authorNickname = authorNickname;
    }

    public String getAuthorAvatarUrl() {
        return authorAvatarUrl;
    }

    public void setAuthorAvatarUrl(String authorAvatarUrl) {
        this.authorAvatarUrl = authorAvatarUrl;
    }
}
