// Copyright 2000-2018 JetBrains s.r.o.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package org.jetbrains.vuejs.codeInsight

import com.intellij.lang.javascript.psi.JSObjectLiteralExpression
import com.intellij.lang.javascript.psi.JSProperty
import com.intellij.psi.PsiElement
import com.intellij.psi.impl.source.html.dtd.HtmlElementDescriptorImpl
import com.intellij.psi.impl.source.html.dtd.HtmlNSDescriptorImpl
import com.intellij.psi.meta.PsiPresentableMetaData
import com.intellij.psi.xml.XmlElement
import com.intellij.psi.xml.XmlTag
import com.intellij.util.ArrayUtil
import com.intellij.xml.XmlAttributeDescriptor
import com.intellij.xml.XmlAttributeDescriptorsProvider
import com.intellij.xml.impl.BasicXmlAttributeDescriptor
import icons.VuejsIcons
import org.jetbrains.annotations.NonNls
import org.jetbrains.vuejs.VueLanguage
import org.jetbrains.vuejs.codeInsight.VueAttributesProvider.Companion.isBinding
import org.jetbrains.vuejs.codeInsight.VueComponentDetailsProvider.Companion.attributeAllowsNoValue
import javax.swing.Icon

class VueAttributesProvider : XmlAttributeDescriptorsProvider {
  companion object {
    val SCOPED = "scoped"
    @NonNls private val SRC_ATTR_NAME = "src"
    private val DEFAULT_BINDABLE = arrayOf("key", "is")
    val DEFAULT = setOf("v-text", "v-html", "v-show", "v-if", "v-else", "v-else-if", "v-for",
                          "v-on", "v-bind", "v-model", "v-pre", "v-cloak","v-once",
                          "slot", "ref").
                  plus(DEFAULT_BINDABLE.map { "v-bind:" + it }).
                  plus(DEFAULT_BINDABLE.map { ":" + it })
    val HAVE_NO_PARAMS = setOf("v-else", "v-once", "v-pre", "v-cloak", "scoped")
    val HAVE_JS_AS_VALUE = DEFAULT - "slot" - "ref"

    fun vueAttributeDescriptor(attributeName: String?): VueAttributeDescriptor? {
      if (DEFAULT.contains(attributeName!!)) return VueAttributeDescriptor(attributeName)
      return null
    }

    fun getDefaultVueAttributes() = DEFAULT.map { VueAttributeDescriptor(it) }.toTypedArray()
    fun isBinding(name: String) = name.startsWith(":") || name.startsWith("v-bind:")

    fun addBindingAttributes(result: MutableList<XmlAttributeDescriptor>,
                             commonAttributes: Array<out XmlAttributeDescriptor>) {
      result.addAll(commonAttributes.map { VueAttributeDescriptor(":" + it.name, it.declaration) })
      result.addAll(commonAttributes.map { VueAttributeDescriptor("v-bind:" + it.name, it.declaration) })
    }
  }

  override fun getAttributeDescriptors(context: XmlTag?): Array<out XmlAttributeDescriptor> {
    if (context == null || !org.jetbrains.vuejs.index.hasVue(context.project)) return emptyArray()
    val result = mutableListOf<XmlAttributeDescriptor>()
    result.addAll(getDefaultVueAttributes())

    // v-bind:any-standard-attribute support
    val commonAttributes = (context.descriptor as? HtmlElementDescriptorImpl)?.getDefaultAttributeDescriptors(context)
    if (commonAttributes != null) {
      addBindingAttributes(result, commonAttributes)
    }

    if (insideStyle(context)) {
      result.add(VueAttributeDescriptor(SCOPED))
      result.add(VueAttributeDescriptor(SRC_ATTR_NAME))
    }
    result.addAll(VueDirectivesProvider.getAttributes(findLocalDescriptor(context), context.project))
    return result.toTypedArray()
  }

  private fun findLocalDescriptor(context: XmlTag): JSObjectLiteralExpression? {
    val scriptWithExport = findScriptWithExport(context.containingFile.originalFile) ?: return null
    return scriptWithExport.second.stubSafeElement as? JSObjectLiteralExpression
  }

  override fun getAttributeDescriptor(attributeName: String?, context: XmlTag?): XmlAttributeDescriptor? {
    if (context == null || !org.jetbrains.vuejs.index.hasVue(context.project) || attributeName == null) return null
    if (attributeName in arrayOf(SCOPED, SRC_ATTR_NAME) && insideStyle(context)) {
      return VueAttributeDescriptor(attributeName)
    }
    val fromDirective = VueDirectivesProvider.resolveAttribute(findLocalDescriptor(context), attributeName, context.project)
    if (fromDirective != null) return fromDirective
    val extractedName = VueComponentDetailsProvider.getBoundName(attributeName)
    if (extractedName != null) {
      return HtmlNSDescriptorImpl.getCommonAttributeDescriptor(extractedName, context) ?: VueAttributeDescriptor(attributeName)
    }
    return vueAttributeDescriptor(attributeName)
  }

  private fun insideStyle(context: XmlTag) = "style" == context.name && context.containingFile?.language == VueLanguage.INSTANCE
}

class VueAttributeDescriptor(private val name:String,
                             private val element:PsiElement? = null,
                             private val isDirective: Boolean = false,
                             private val isNonProp: Boolean = false) : BasicXmlAttributeDescriptor(), PsiPresentableMetaData {
  override fun getName() = name
  override fun getDeclaration() = element
  override fun init(element: PsiElement?) {}
  override fun isRequired(): Boolean {
    if (isBinding(name)) return false
    val initializer = (element as? JSProperty)?.objectLiteralExpressionInitializer ?: return false
    val literal = findProperty(initializer, "required")?.literalExpressionInitializer
    return literal != null && literal.isBooleanLiteral && "true" == literal.significantValue
  }

  override fun isFixed() = false
  override fun hasIdType() = false
  override fun getDependences(): Array<out Any> = ArrayUtil.EMPTY_OBJECT_ARRAY
  override fun getEnumeratedValueDeclaration(xmlElement: XmlElement?, value: String?): PsiElement? {
    return if (isEnumerated) xmlElement else super.getEnumeratedValueDeclaration(xmlElement, value)
  }

  override fun hasIdRefType() = false
  override fun getDefaultValue() = null
  override fun isEnumerated() = isDirective || isNonProp ||
                                VueAttributesProvider.HAVE_NO_PARAMS.contains(name) || attributeAllowsNoValue(name)
  override fun getEnumeratedValues(): Array<out String> {
    if (isEnumerated) {
      return arrayOf(name)
    }
    return ArrayUtil.EMPTY_STRING_ARRAY
  }
  override fun getTypeName() = null
  override fun getIcon(): Icon = VuejsIcons.Vue

  fun createNameVariant(newName: String) : VueAttributeDescriptor {
    if (newName == name) return this
    return VueAttributeDescriptor(newName, element)
  }

  fun isDirective(): Boolean = isDirective
}

fun findProperty(obj: JSObjectLiteralExpression?, name:String) = obj?.properties?.find { it.name == name }