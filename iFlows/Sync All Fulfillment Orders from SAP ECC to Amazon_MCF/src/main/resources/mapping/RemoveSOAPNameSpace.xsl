<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet xmlns:xsl="http://www.w3.org/1999/XSL/Transform" version="1.0">
    <!-- Identity template: copies everything as is -->
    <xsl:template match="@*|node()">
        <xsl:copy>
            <xsl:apply-templates select="@*|node()" />
        </xsl:copy>
    </xsl:template>

    <!-- Template to strip namespace from elements -->
    <xsl:template match="*">
        <xsl:element name="{local-name()}">
            <xsl:apply-templates select="@*|node()" />
        </xsl:element>
    </xsl:template>
</xsl:stylesheet>
