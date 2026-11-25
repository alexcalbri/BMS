package com.ideasdeveloper.bms.server.company.domain

data class CompanyProfile(
    val id: String,
    val code: String,
    val name: String,
    val databaseName: String,
    val isActive: Boolean,
    val branding: CompanyBranding,
)
