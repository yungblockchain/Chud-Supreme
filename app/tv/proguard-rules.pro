# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

-dontwarn reactor.blockhound.integration.BlockHoundIntegration

-dontwarn io.netty.internal.tcnative.AsyncSSLPrivateKeyMethod
-dontwarn io.netty.internal.tcnative.AsyncTask
-dontwarn io.netty.internal.tcnative.Buffer
-dontwarn io.netty.internal.tcnative.CertificateCallback
-dontwarn io.netty.internal.tcnative.CertificateCompressionAlgo
-dontwarn io.netty.internal.tcnative.CertificateVerifier
-dontwarn io.netty.internal.tcnative.Library
-dontwarn io.netty.internal.tcnative.SSL
-dontwarn io.netty.internal.tcnative.SSLContext
-dontwarn io.netty.internal.tcnative.SSLPrivateKeyMethod
-dontwarn io.netty.internal.tcnative.SSLSessionCache
-dontwarn io.netty.internal.tcnative.SessionTicketKey
-dontwarn io.netty.internal.tcnative.SniHostNameMatcher
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean
-dontwarn jdk.jfr.Category
-dontwarn jdk.jfr.DataAmount
-dontwarn jdk.jfr.Description
-dontwarn jdk.jfr.Enabled
-dontwarn jdk.jfr.Event
-dontwarn jdk.jfr.FlightRecorder
-dontwarn jdk.jfr.Label
-dontwarn jdk.jfr.MemoryAddress
-dontwarn jdk.jfr.Name
-dontwarn org.apache.log4j.Level
-dontwarn org.apache.log4j.Logger
-dontwarn org.apache.log4j.Priority
-dontwarn org.apache.logging.log4j.Level
-dontwarn org.apache.logging.log4j.LogManager
-dontwarn org.apache.logging.log4j.Logger
-dontwarn org.apache.logging.log4j.message.MessageFactory
-dontwarn org.apache.logging.log4j.spi.ExtendedLogger
-dontwarn org.apache.logging.log4j.spi.ExtendedLoggerWrapper


# CHUD STREAMS: the TV app runs the same data layer as the phone app (Ktor/Netty server for
# phone pairing, kotlinx.serialization, Retrofit, DLNA), but its release rules had none of the
# phone app's keep rules. Without them R8 stripped Netty's NioServerSocketChannel constructor,
# which Netty creates by reflection, and the app crashed a second after launch.
# These are copied from app/smartphone/proguard-rules.pro.
-dontwarn io.netty.internal.tcnative.ResultCallback
-dontwarn io.netty.internal.tcnative.SSLSession
-dontwarn io.netty.pkitesting.**
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-dontnote kotlinx.serialization.**
-keepclassmembers public class **$$serializer {
    private ** descriptor;
}
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-if interface * { @retrofit2.http.* <methods>; }
-keep,allowobfuscation interface <1>
-if interface * { @retrofit2.http.* <methods>; }
-keep,allowobfuscation interface * extends <1>
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-keep class org.jupnp.** { *; }
-keep class io.netty.** {*; }
-keep class io.ktor.** {*; }
-keep class kotlin.reflect.jvm.internal.** { *; }

# Optional libraries referenced by the kept Netty/Ktor classes (as in the phone app's rules).
-dontwarn org.bouncycastle.jsse.BCSSLParameters
-dontwarn org.bouncycastle.jsse.BCSSLSocket
-dontwarn org.bouncycastle.jsse.provider.BouncyCastleJsseProvider
-dontwarn org.conscrypt.Conscrypt$Version
-dontwarn org.conscrypt.Conscrypt
-dontwarn org.conscrypt.ConscryptHostnameVerifier
-dontwarn org.openjsse.javax.net.ssl.SSLParameters
-dontwarn org.openjsse.javax.net.ssl.SSLSocket
-dontwarn org.openjsse.net.ssl.OpenJSSE
-dontwarn java.lang.invoke.StringConcatFactory
-dontwarn kotlinx.serialization.internal.ClassValueReferences
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn javax.annotation.**
-dontwarn kotlin.Unit
-dontwarn retrofit2.KotlinExtensions
-dontwarn retrofit2.KotlinExtensions$*
-dontwarn com.sun.net.httpserver.HttpContext
-dontwarn com.sun.net.httpserver.HttpHandler
-dontwarn com.sun.net.httpserver.HttpServer
-dontwarn javax.enterprise.context.ApplicationScoped
-dontwarn javax.enterprise.inject.Alternative
-dontwarn sun.net.www.protocol.http.Handler
-dontwarn com.sun.net.httpserver.Headers
-dontwarn com.sun.net.httpserver.HttpExchange
-dontwarn java.awt.BorderLayout
-dontwarn java.awt.Component
-dontwarn java.awt.Container
-dontwarn java.awt.Dimension
-dontwarn java.awt.Frame
-dontwarn java.awt.LayoutManager
-dontwarn java.awt.Rectangle
-dontwarn java.awt.Toolkit
-dontwarn java.awt.Window
-dontwarn java.awt.datatransfer.Clipboard
-dontwarn java.awt.datatransfer.ClipboardOwner
-dontwarn java.awt.datatransfer.StringSelection
-dontwarn java.awt.datatransfer.Transferable
-dontwarn java.awt.event.ActionEvent
-dontwarn java.awt.event.ActionListener
-dontwarn java.awt.event.ItemListener
-dontwarn java.awt.event.WindowAdapter
-dontwarn java.awt.event.WindowEvent
-dontwarn java.awt.event.WindowListener
-dontwarn javax.enterprise.event.Event
-dontwarn javax.enterprise.event.Observes
-dontwarn javax.enterprise.inject.Any
-dontwarn javax.enterprise.inject.Default
-dontwarn javax.enterprise.inject.Instance
-dontwarn javax.enterprise.util.AnnotationLiteral
-dontwarn javax.swing.AbstractAction
-dontwarn javax.swing.AbstractButton
-dontwarn javax.swing.BorderFactory
-dontwarn javax.swing.Box
-dontwarn javax.swing.BoxLayout
-dontwarn javax.swing.Icon
-dontwarn javax.swing.ImageIcon
-dontwarn javax.swing.JButton
-dontwarn javax.swing.JCheckBox
-dontwarn javax.swing.JComboBox
-dontwarn javax.swing.JDialog
-dontwarn javax.swing.JFrame
-dontwarn javax.swing.JLabel
-dontwarn javax.swing.JPanel
-dontwarn javax.swing.JScrollPane
-dontwarn javax.swing.JTable
-dontwarn javax.swing.JTextArea
-dontwarn javax.swing.JToolBar
-dontwarn javax.swing.JTree
-dontwarn javax.swing.JWindow
-dontwarn javax.swing.ListSelectionModel
-dontwarn javax.swing.SwingUtilities
-dontwarn javax.swing.UIManager
-dontwarn javax.swing.border.Border
-dontwarn javax.swing.border.TitledBorder
-dontwarn javax.swing.event.ListSelectionEvent
-dontwarn javax.swing.event.ListSelectionListener
-dontwarn javax.swing.event.TreeExpansionEvent
-dontwarn javax.swing.event.TreeWillExpandListener
-dontwarn javax.swing.table.AbstractTableModel
-dontwarn javax.swing.table.DefaultTableCellRenderer
-dontwarn javax.swing.table.JTableHeader
-dontwarn javax.swing.table.TableCellRenderer
-dontwarn javax.swing.table.TableColumn
-dontwarn javax.swing.table.TableColumnModel
-dontwarn javax.swing.table.TableModel
-dontwarn javax.swing.tree.DefaultMutableTreeNode
-dontwarn javax.swing.tree.DefaultTreeCellRenderer
-dontwarn javax.swing.tree.DefaultTreeModel
-dontwarn javax.swing.tree.MutableTreeNode
-dontwarn javax.swing.tree.TreeCellRenderer
-dontwarn javax.swing.tree.TreeModel
-dontwarn javax.swing.tree.TreeNode
-dontwarn javax.swing.tree.TreePath
-dontwarn javax.swing.tree.TreeSelectionModel
-dontwarn sun.net.www.protocol.http.HttpURLConnection
-dontwarn org.osgi.framework.BundleContext
-dontwarn org.osgi.framework.ServiceReference
-dontwarn org.osgi.framework.ServiceRegistration
-dontwarn org.osgi.service.cm.ManagedService
-dontwarn org.osgi.service.component.ComponentContext
-dontwarn org.osgi.service.http.HttpContext
-dontwarn org.osgi.service.http.HttpService
-dontwarn org.osgi.service.http.NamespaceException
-dontwarn org.eclipse.jetty.npn.NextProtoNego$ClientProvider
-dontwarn org.eclipse.jetty.npn.NextProtoNego$Provider
-dontwarn org.eclipse.jetty.npn.NextProtoNego$ServerProvider
-dontwarn org.eclipse.jetty.npn.NextProtoNego
-dontwarn java.lang.Module
-dontwarn javax.naming.ldap.LdapName
-dontwarn javax.naming.ldap.Rdn
-dontwarn org.eclipse.jetty.alpn.ALPN$Provider
-dontwarn org.eclipse.jetty.alpn.ALPN$ServerProvider
-dontwarn org.eclipse.jetty.alpn.ALPN
-dontwarn com.aayushatharva.brotli4j.Brotli4jLoader
-dontwarn com.aayushatharva.brotli4j.decoder.DecoderJNI$Status
-dontwarn com.aayushatharva.brotli4j.decoder.DecoderJNI$Wrapper
-dontwarn com.aayushatharva.brotli4j.encoder.BrotliEncoderChannel
-dontwarn com.aayushatharva.brotli4j.encoder.Encoder$Mode
-dontwarn com.aayushatharva.brotli4j.encoder.Encoder$Parameters
-dontwarn com.github.luben.zstd.Zstd
-dontwarn com.google.protobuf.ExtensionRegistry
-dontwarn com.google.protobuf.ExtensionRegistryLite
-dontwarn com.google.protobuf.MessageLite$Builder
-dontwarn com.google.protobuf.MessageLite
-dontwarn com.google.protobuf.MessageLiteOrBuilder
-dontwarn com.google.protobuf.Parser
-dontwarn com.google.protobuf.nano.CodedOutputByteBufferNano
-dontwarn com.google.protobuf.nano.MessageNano
-dontwarn com.jcraft.jzlib.Deflater
-dontwarn com.jcraft.jzlib.Inflater
-dontwarn com.jcraft.jzlib.JZlib$WrapperType
-dontwarn com.jcraft.jzlib.JZlib
-dontwarn com.ning.compress.BufferRecycler
-dontwarn com.ning.compress.lzf.ChunkDecoder
-dontwarn com.ning.compress.lzf.ChunkEncoder
-dontwarn com.ning.compress.lzf.LZFChunk
-dontwarn com.ning.compress.lzf.LZFEncoder
-dontwarn com.ning.compress.lzf.util.ChunkDecoderFactory
-dontwarn com.ning.compress.lzf.util.ChunkEncoderFactory
-dontwarn com.oracle.svm.core.annotate.Alias
-dontwarn com.oracle.svm.core.annotate.InjectAccessors
-dontwarn com.oracle.svm.core.annotate.RecomputeFieldValue$Kind
-dontwarn com.oracle.svm.core.annotate.RecomputeFieldValue
-dontwarn com.oracle.svm.core.annotate.TargetClass
-dontwarn jdk.jfr.**
-dontwarn lzma.sdk.ICodeProgress
-dontwarn lzma.sdk.lzma.Encoder
-dontwarn net.jpountz.lz4.LZ4Compressor
-dontwarn net.jpountz.lz4.LZ4Exception
-dontwarn net.jpountz.lz4.LZ4Factory
-dontwarn net.jpountz.lz4.LZ4FastDecompressor
-dontwarn net.jpountz.xxhash.XXHash32
-dontwarn net.jpountz.xxhash.XXHashFactory
-dontwarn org.jboss.marshalling.ByteInput
-dontwarn org.jboss.marshalling.ByteOutput
-dontwarn org.jboss.marshalling.Marshaller
-dontwarn org.jboss.marshalling.MarshallerFactory
-dontwarn org.jboss.marshalling.MarshallingConfiguration
-dontwarn org.jboss.marshalling.Unmarshaller
-dontwarn reactor.blockhound.BlockHound$Builder
-dontwarn sun.security.x509.AlgorithmId
-dontwarn sun.security.x509.CertificateAlgorithmId
-dontwarn sun.security.x509.CertificateSerialNumber
-dontwarn sun.security.x509.CertificateSubjectName
-dontwarn sun.security.x509.CertificateValidity
-dontwarn sun.security.x509.CertificateVersion
-dontwarn sun.security.x509.CertificateX509Key
-dontwarn sun.security.x509.X500Name
-dontwarn sun.security.x509.X509CertImpl
-dontwarn sun.security.x509.X509CertInfo
-dontwarn org.slf4j.impl.StaticLoggerBinder
-dontwarn com.github.luben.zstd.ZstdInputStreamNoFinalizer
-dontwarn com.github.luben.zstd.util.Native
-dontwarn com.sun.nio.file.SensitivityWatchEventModifier
-dontwarn org.osgi.annotation.bundle.Export

# Addon screens are first reached from Settings. Keep them so R8 cannot strip the page and crash on open.
-keep class com.m3u.tv.stremio.** { *; }

## NewPipe Extractor (YouTube): Rhino runs YouTube's player JavaScript by reflection.
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.tools.**
-dontwarn javax.annotation.**
-keep class org.schabi.newpipe.extractor.** { *; }
# Rhino's optional JSON/script-engine bridges reference desktop-Java classes Android lacks.
-dontwarn java.beans.**
-dontwarn javax.script.**
-dontwarn org.mozilla.javascript.engine.**
-dontwarn jdk.dynalink.**
-dontwarn java.lang.invoke.**
-dontwarn javax.lang.model.**
-dontwarn sun.misc.**
