<xsl:stylesheet version="2.0"
    xmlns:xsl="http://www.w3.org/1999/XSL/Transform">

    <xsl:output method="xml" indent="yes" omit-xml-declaration="yes"/>

    <!-- Template to match and copy elements without namespace -->
    <xsl:template match="*">
        <xsl:element name="{local-name()}">
            <xsl:for-each select="@*">
                <xsl:attribute name="{local-name()}">
                    <xsl:value-of select="."/>
                </xsl:attribute>
            </xsl:for-each>
            <xsl:apply-templates/>
        </xsl:element>
    </xsl:template>

    <!-- Template to copy text nodes -->
    <xsl:template match="text() | comment() | processing-instruction()">
        <xsl:copy/>
    </xsl:template>

</xsl:stylesheet>
