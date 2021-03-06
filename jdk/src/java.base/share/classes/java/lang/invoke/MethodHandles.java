/*
 * Copyright (c) 2008, 2016, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package java.lang.invoke;

import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.Opcodes;
import jdk.internal.reflect.CallerSensitive;
import jdk.internal.reflect.Reflection;
import jdk.internal.vm.annotation.ForceInline;
import sun.invoke.util.ValueConversions;
import sun.invoke.util.VerifyAccess;
import sun.invoke.util.Wrapper;
import sun.reflect.misc.ReflectUtil;
import sun.security.util.SecurityConstants;

import java.lang.invoke.LambdaForm.BasicType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ReflectPermission;
import java.nio.ByteOrder;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.lang.invoke.MethodHandleImpl.Intrinsic;
import static java.lang.invoke.MethodHandleNatives.Constants.*;
import static java.lang.invoke.MethodHandleStatics.newIllegalArgumentException;
import static java.lang.invoke.MethodType.methodType;

/**
 * This class consists exclusively of static methods that operate on or return
 * method handles. They fall into several categories:
 * <ul>
 * <li>Lookup methods which help create method handles for methods and fields.
 * <li>Combinator methods, which combine or transform pre-existing method handles into new ones.
 * <li>Other factory methods to create method handles that emulate other common JVM operations or control flow patterns.
 * </ul>
 *
 * @author John Rose, JSR 292 EG
 * @since 1.7
 */
public class MethodHandles {

    private MethodHandles() { }  // do not instantiate

    static final MemberName.Factory IMPL_NAMES = MemberName.getFactory();

    // See IMPL_LOOKUP below.

    //// Method handle creation from ordinary methods.

    /**
     * Returns a {@link Lookup lookup object} with
     * full capabilities to emulate all supported bytecode behaviors of the caller.
     * These capabilities include <a href="MethodHandles.Lookup.html#privacc">private access</a> to the caller.
     * Factory methods on the lookup object can create
     * <a href="MethodHandleInfo.html#directmh">direct method handles</a>
     * for any member that the caller has access to via bytecodes,
     * including protected and private fields and methods.
     * This lookup object is a <em>capability</em> which may be delegated to trusted agents.
     * Do not store it in place where untrusted code can access it.
     * <p>
     * This method is caller sensitive, which means that it may return different
     * values to different callers.
     * <p>
     * For any given caller class {@code C}, the lookup object returned by this call
     * has equivalent capabilities to any lookup object
     * supplied by the JVM to the bootstrap method of an
     * <a href="package-summary.html#indyinsn">invokedynamic instruction</a>
     * executing in the same caller class {@code C}.
     * @return a lookup object for the caller of this method, with private access
     */
    @CallerSensitive
    @ForceInline // to ensure Reflection.getCallerClass optimization
    public static Lookup lookup() {
        return new Lookup(Reflection.getCallerClass());
    }

    /**
     * Returns a {@link Lookup lookup object} which is trusted minimally.
     * It can only be used to create method handles to public members in
     * public classes in packages that are exported unconditionally.
     * <p>
     * For now, the {@linkplain Lookup#lookupClass lookup class} of this lookup
     * object is in an unnamed module.
     * Consequently, the lookup context of this lookup object will be the bootstrap
     * class loader, which means it cannot find user classes.
     *
     * <p style="font-size:smaller;">
     * <em>Discussion:</em>
     * The lookup class can be changed to any other class {@code C} using an expression of the form
     * {@link Lookup#in publicLookup().in(C.class)}.
     * but may change the lookup context by virtue of changing the class loader.
     * A public lookup object is always subject to
     * <a href="MethodHandles.Lookup.html#secmgr">security manager checks</a>.
     * Also, it cannot access
     * <a href="MethodHandles.Lookup.html#callsens">caller sensitive methods</a>.
     * @return a lookup object which is trusted minimally
     */
    public static Lookup publicLookup() {
        // During VM startup then only classes in the java.base module can be
        // loaded and linked. This is because java.base exports aren't setup until
        // the module system is initialized, hence types in the unnamed module
        // (or any named module) can't link to java/lang/Object.
        if (!jdk.internal.misc.VM.isModuleSystemInited()) {
            return new Lookup(Object.class, Lookup.PUBLIC);
        } else {
            return LookupHelper.PUBLIC_LOOKUP;
        }
    }

    /**
     * Performs an unchecked "crack" of a
     * <a href="MethodHandleInfo.html#directmh">direct method handle</a>.
     * The result is as if the user had obtained a lookup object capable enough
     * to crack the target method handle, called
     * {@link java.lang.invoke.MethodHandles.Lookup#revealDirect Lookup.revealDirect}
     * on the target to obtain its symbolic reference, and then called
     * {@link java.lang.invoke.MethodHandleInfo#reflectAs MethodHandleInfo.reflectAs}
     * to resolve the symbolic reference to a member.
     * <p>
     * If there is a security manager, its {@code checkPermission} method
     * is called with a {@code ReflectPermission("suppressAccessChecks")} permission.
     * @param <T> the desired type of the result, either {@link Member} or a subtype
     * @param target a direct method handle to crack into symbolic reference components
     * @param expected a class object representing the desired result type {@code T}
     * @return a reference to the method, constructor, or field object
     * @exception SecurityException if the caller is not privileged to call {@code setAccessible}
     * @exception NullPointerException if either argument is {@code null}
     * @exception IllegalArgumentException if the target is not a direct method handle
     * @exception ClassCastException if the member is not of the expected type
     * @since 1.8
     */
    public static <T extends Member> T
    reflectAs(Class<T> expected, MethodHandle target) {
        SecurityManager smgr = System.getSecurityManager();
        if (smgr != null)  smgr.checkPermission(ACCESS_PERMISSION);
        Lookup lookup = Lookup.IMPL_LOOKUP;  // use maximally privileged lookup
        return lookup.revealDirect(target).reflectAs(expected, lookup);
    }
    // Copied from AccessibleObject, as used by Method.setAccessible, etc.:
    private static final java.security.Permission ACCESS_PERMISSION =
        new ReflectPermission("suppressAccessChecks");

    /**
     * A <em>lookup object</em> is a factory for creating method handles,
     * when the creation requires access checking.
     * Method handles do not perform
     * access checks when they are called, but rather when they are created.
     * Therefore, method handle access
     * restrictions must be enforced when a method handle is created.
     * The caller class against which those restrictions are enforced
     * is known as the {@linkplain #lookupClass lookup class}.
     * <p>
     * A lookup class which needs to create method handles will call
     * {@link MethodHandles#lookup MethodHandles.lookup} to create a factory for itself.
     * When the {@code Lookup} factory object is created, the identity of the lookup class is
     * determined, and securely stored in the {@code Lookup} object.
     * The lookup class (or its delegates) may then use factory methods
     * on the {@code Lookup} object to create method handles for access-checked members.
     * This includes all methods, constructors, and fields which are allowed to the lookup class,
     * even private ones.
     *
     * <h1><a name="lookups"></a>Lookup Factory Methods</h1>
     * The factory methods on a {@code Lookup} object correspond to all major
     * use cases for methods, constructors, and fields.
     * Each method handle created by a factory method is the functional
     * equivalent of a particular <em>bytecode behavior</em>.
     * (Bytecode behaviors are described in section 5.4.3.5 of the Java Virtual Machine Specification.)
     * Here is a summary of the correspondence between these factory methods and
     * the behavior of the resulting method handles:
     * <table border=1 cellpadding=5 summary="lookup method behaviors">
     * <tr>
     *     <th><a name="equiv"></a>lookup expression</th>
     *     <th>member</th>
     *     <th>bytecode behavior</th>
     * </tr>
     * <tr>
     *     <td>{@link java.lang.invoke.MethodHandles.Lookup#findGetter lookup.findGetter(C.class,"f",FT.class)}</td>
     *     <td>{@code FT f;}</td><td>{@code (T) this.f;}</td>
     * </tr>
     * <tr>
     *     <td>{@link java.lang.invoke.MethodHandles.Lookup#findStaticGetter lookup.findStaticGetter(C.class,"f",FT.class)}</td>
     *     <td>{@code static}<br>{@code FT f;}</td><td>{@code (T) C.f;}</td>
     * </tr>
     * <tr>
     *     <td>{@link java.lang.invoke.MethodHandles.Lookup#findSetter lookup.findSetter(C.class,"f",FT.class)}</td>
     *     <td>{@code FT f;}</td><td>{@code this.f = x;}</td>
     * </tr>
     * <tr>
     *     <td>{@link java.lang.invoke.MethodHandles.Lookup#findStaticSetter lookup.findStaticSetter(C.class,"f",FT.class)}</td>
     *     <td>{@code static}<br>{@code FT f;}</td><td>{@code C.f = arg;}</td>
     * </tr>
     * <tr>
     *     <td>{@link java.lang.invoke.MethodHandles.Lookup#findVirtual lookup.findVirtual(C.class,"m",MT)}</td>
     *     <td>{@code T m(A*);}</td><td>{@code (T) this.m(arg*);}</td>
     * </tr>
     * <tr>
     *     <td>{@link java.lang.invoke.MethodHandles.Lookup#findStatic lookup.findStatic(C.class,"m",MT)}</td>
     *     <td>{@code static}<br>{@code T m(A*);}</td><td>{@code (T) C.m(arg*);}</td>
     * </tr>
     * <tr>
     *     <td>{@link java.lang.invoke.MethodHandles.Lookup#findSpecial lookup.findSpecial(C.class,"m",MT,this.class)}</td>
     *     <td>{@code T m(A*);}</td><td>{@code (T) super.m(arg*);}</td>
     * </tr>
     * <tr>
     *     <td>{@link java.lang.invoke.MethodHandles.Lookup#findConstructor lookup.findConstructor(C.class,MT)}</td>
     *     <td>{@code C(A*);}</td><td>{@code new C(arg*);}</td>
     * </tr>
     * <tr>
     *     <td>{@link java.lang.invoke.MethodHandles.Lookup#unreflectGetter lookup.unreflectGetter(aField)}</td>
     *     <td>({@code static})?<br>{@code FT f;}</td><td>{@code (FT) aField.get(thisOrNull);}</td>
     * </tr>
     * <tr>
     *     <td>{@link java.lang.invoke.MethodHandles.Lookup#unreflectSetter lookup.unreflectSetter(aField)}</td>
     *     <td>({@code static})?<br>{@code FT f;}</td><td>{@code aField.set(thisOrNull, arg);}</td>
     * </tr>
     * <tr>
     *     <td>{@link java.lang.invoke.MethodHandles.Lookup#unreflect lookup.unreflect(aMethod)}</td>
     *     <td>({@code static})?<br>{@code T m(A*);}</td><td>{@code (T) aMethod.invoke(thisOrNull, arg*);}</td>
     * </tr>
     * <tr>
     *     <td>{@link java.lang.invoke.MethodHandles.Lookup#unreflectConstructor lookup.unreflectConstructor(aConstructor)}</td>
     *     <td>{@code C(A*);}</td><td>{@code (C) aConstructor.newInstance(arg*);}</td>
     * </tr>
     * <tr>
     *     <td>{@link java.lang.invoke.MethodHandles.Lookup#unreflect lookup.unreflect(aMethod)}</td>
     *     <td>({@code static})?<br>{@code T m(A*);}</td><td>{@code (T) aMethod.invoke(thisOrNull, arg*);}</td>
     * </tr>
     * <tr>
     *     <td>{@link java.lang.invoke.MethodHandles.Lookup#findClass lookup.findClass("C")}</td>
     *     <td>{@code class C { ... }}</td><td>{@code C.class;}</td>
     * </tr>
     * </table>
     *
     * Here, the type {@code C} is the class or interface being searched for a member,
     * documented as a parameter named {@code refc} in the lookup methods.
     * The method type {@code MT} is composed from the return type {@code T}
     * and the sequence of argument types {@code A*}.
     * The constructor also has a sequence of argument types {@code A*} and
     * is deemed to return the newly-created object of type {@code C}.
     * Both {@code MT} and the field type {@code FT} are documented as a parameter named {@code type}.
     * The formal parameter {@code this} stands for the self-reference of type {@code C};
     * if it is present, it is always the leading argument to the method handle invocation.
     * (In the case of some {@code protected} members, {@code this} may be
     * restricted in type to the lookup class; see below.)
     * The name {@code arg} stands for all the other method handle arguments.
     * In the code examples for the Core Reflection API, the name {@code thisOrNull}
     * stands for a null reference if the accessed method or field is static,
     * and {@code this} otherwise.
     * The names {@code aMethod}, {@code aField}, and {@code aConstructor} stand
     * for reflective objects corresponding to the given members.
     * <p>
     * The bytecode behavior for a {@code findClass} operation is a load of a constant class,
     * as if by {@code ldc CONSTANT_Class}.
     * The behavior is represented, not as a method handle, but directly as a {@code Class} constant.
     * <p>
     * In cases where the given member is of variable arity (i.e., a method or constructor)
     * the returned method handle will also be of {@linkplain MethodHandle#asVarargsCollector variable arity}.
     * In all other cases, the returned method handle will be of fixed arity.
     * <p style="font-size:smaller;">
     * <em>Discussion:</em>
     * The equivalence between looked-up method handles and underlying
     * class members and bytecode behaviors
     * can break down in a few ways:
     * <ul style="font-size:smaller;">
     * <li>If {@code C} is not symbolically accessible from the lookup class's loader,
     * the lookup can still succeed, even when there is no equivalent
     * Java expression or bytecoded constant.
     * <li>Likewise, if {@code T} or {@code MT}
     * is not symbolically accessible from the lookup class's loader,
     * the lookup can still succeed.
     * For example, lookups for {@code MethodHandle.invokeExact} and
     * {@code MethodHandle.invoke} will always succeed, regardless of requested type.
     * <li>If there is a security manager installed, it can forbid the lookup
     * on various grounds (<a href="MethodHandles.Lookup.html#secmgr">see below</a>).
     * By contrast, the {@code ldc} instruction on a {@code CONSTANT_MethodHandle}
     * constant is not subject to security manager checks.
     * <li>If the looked-up method has a
     * <a href="MethodHandle.html#maxarity">very large arity</a>,
     * the method handle creation may fail, due to the method handle
     * type having too many parameters.
     * </ul>
     *
     * <h1><a name="access"></a>Access checking</h1>
     * Access checks are applied in the factory methods of {@code Lookup},
     * when a method handle is created.
     * This is a key difference from the Core Reflection API, since
     * {@link java.lang.reflect.Method#invoke java.lang.reflect.Method.invoke}
     * performs access checking against every caller, on every call.
     * <p>
     * All access checks start from a {@code Lookup} object, which
     * compares its recorded lookup class against all requests to
     * create method handles.
     * A single {@code Lookup} object can be used to create any number
     * of access-checked method handles, all checked against a single
     * lookup class.
     * <p>
     * A {@code Lookup} object can be shared with other trusted code,
     * such as a metaobject protocol.
     * A shared {@code Lookup} object delegates the capability
     * to create method handles on private members of the lookup class.
     * Even if privileged code uses the {@code Lookup} object,
     * the access checking is confined to the privileges of the
     * original lookup class.
     * <p>
     * A lookup can fail, because
     * the containing class is not accessible to the lookup class, or
     * because the desired class member is missing, or because the
     * desired class member is not accessible to the lookup class, or
     * because the lookup object is not trusted enough to access the member.
     * In any of these cases, a {@code ReflectiveOperationException} will be
     * thrown from the attempted lookup.  The exact class will be one of
     * the following:
     * <ul>
     * <li>NoSuchMethodException &mdash; if a method is requested but does not exist
     * <li>NoSuchFieldException &mdash; if a field is requested but does not exist
     * <li>IllegalAccessException &mdash; if the member exists but an access check fails
     * </ul>
     * <p>
     * In general, the conditions under which a method handle may be
     * looked up for a method {@code M} are no more restrictive than the conditions
     * under which the lookup class could have compiled, verified, and resolved a call to {@code M}.
     * Where the JVM would raise exceptions like {@code NoSuchMethodError},
     * a method handle lookup will generally raise a corresponding
     * checked exception, such as {@code NoSuchMethodException}.
     * And the effect of invoking the method handle resulting from the lookup
     * is <a href="MethodHandles.Lookup.html#equiv">exactly equivalent</a>
     * to executing the compiled, verified, and resolved call to {@code M}.
     * The same point is true of fields and constructors.
     * <p style="font-size:smaller;">
     * <em>Discussion:</em>
     * Access checks only apply to named and reflected methods,
     * constructors, and fields.
     * Other method handle creation methods, such as
     * {@link MethodHandle#asType MethodHandle.asType},
     * do not require any access checks, and are used
     * independently of any {@code Lookup} object.
     * <p>
     * If the desired member is {@code protected}, the usual JVM rules apply,
     * including the requirement that the lookup class must be either be in the
     * same package as the desired member, or must inherit that member.
     * (See the Java Virtual Machine Specification, sections 4.9.2, 5.4.3.5, and 6.4.)
     * In addition, if the desired member is a non-static field or method
     * in a different package, the resulting method handle may only be applied
     * to objects of the lookup class or one of its subclasses.
     * This requirement is enforced by narrowing the type of the leading
     * {@code this} parameter from {@code C}
     * (which will necessarily be a superclass of the lookup class)
     * to the lookup class itself.
     * <p>
     * The JVM imposes a similar requirement on {@code invokespecial} instruction,
     * that the receiver argument must match both the resolved method <em>and</em>
     * the current class.  Again, this requirement is enforced by narrowing the
     * type of the leading parameter to the resulting method handle.
     * (See the Java Virtual Machine Specification, section 4.10.1.9.)
     * <p>
     * The JVM represents constructors and static initializer blocks as internal methods
     * with special names ({@code "<init>"} and {@code "<clinit>"}).
     * The internal syntax of invocation instructions allows them to refer to such internal
     * methods as if they were normal methods, but the JVM bytecode verifier rejects them.
     * A lookup of such an internal method will produce a {@code NoSuchMethodException}.
     * <p>
     * In some cases, access between nested classes is obtained by the Java compiler by creating
     * an wrapper method to access a private method of another class
     * in the same top-level declaration.
     * For example, a nested class {@code C.D}
     * can access private members within other related classes such as
     * {@code C}, {@code C.D.E}, or {@code C.B},
     * but the Java compiler may need to generate wrapper methods in
     * those related classes.  In such cases, a {@code Lookup} object on
     * {@code C.E} would be unable to those private members.
     * A workaround for this limitation is the {@link Lookup#in Lookup.in} method,
     * which can transform a lookup on {@code C.E} into one on any of those other
     * classes, without special elevation of privilege.
     * <p>
     * The accesses permitted to a given lookup object may be limited,
     * according to its set of {@link #lookupModes lookupModes},
     * to a subset of members normally accessible to the lookup class.
     * For example, the {@link MethodHandles#publicLookup publicLookup}
     * method produces a lookup object which is only allowed to access
     * public members in public classes of exported packages.
     * The caller sensitive method {@link MethodHandles#lookup lookup}
     * produces a lookup object with full capabilities relative to
     * its caller class, to emulate all supported bytecode behaviors.
     * Also, the {@link Lookup#in Lookup.in} method may produce a lookup object
     * with fewer access modes than the original lookup object.
     *
     * <p style="font-size:smaller;">
     * <a name="privacc"></a>
     * <em>Discussion of private access:</em>
     * We say that a lookup has <em>private access</em>
     * if its {@linkplain #lookupModes lookup modes}
     * include the possibility of accessing {@code private} members.
     * As documented in the relevant methods elsewhere,
     * only lookups with private access possess the following capabilities:
     * <ul style="font-size:smaller;">
     * <li>access private fields, methods, and constructors of the lookup class
     * <li>create method handles which invoke <a href="MethodHandles.Lookup.html#callsens">caller sensitive</a> methods,
     *     such as {@code Class.forName}
     * <li>create method handles which {@link Lookup#findSpecial emulate invokespecial} instructions
     * <li>avoid <a href="MethodHandles.Lookup.html#secmgr">package access checks</a>
     *     for classes accessible to the lookup class
     * <li>create {@link Lookup#in delegated lookup objects} which have private access to other classes
     *     within the same package member
     * </ul>
     * <p style="font-size:smaller;">
     * Each of these permissions is a consequence of the fact that a lookup object
     * with private access can be securely traced back to an originating class,
     * whose <a href="MethodHandles.Lookup.html#equiv">bytecode behaviors</a> and Java language access permissions
     * can be reliably determined and emulated by method handles.
     *
     * <h1><a name="secmgr"></a>Security manager interactions</h1>
     * Although bytecode instructions can only refer to classes in
     * a related class loader, this API can search for methods in any
     * class, as long as a reference to its {@code Class} object is
     * available.  Such cross-loader references are also possible with the
     * Core Reflection API, and are impossible to bytecode instructions
     * such as {@code invokestatic} or {@code getfield}.
     * There is a {@linkplain java.lang.SecurityManager security manager API}
     * to allow applications to check such cross-loader references.
     * These checks apply to both the {@code MethodHandles.Lookup} API
     * and the Core Reflection API
     * (as found on {@link java.lang.Class Class}).
     * <p>
     * If a security manager is present, member and class lookups are subject to
     * additional checks.
     * From one to three calls are made to the security manager.
     * Any of these calls can refuse access by throwing a
     * {@link java.lang.SecurityException SecurityException}.
     * Define {@code smgr} as the security manager,
     * {@code lookc} as the lookup class of the current lookup object,
     * {@code refc} as the containing class in which the member
     * is being sought, and {@code defc} as the class in which the
     * member is actually defined.
     * (If a class or other type is being accessed,
     * the {@code refc} and {@code defc} values are the class itself.)
     * The value {@code lookc} is defined as <em>not present</em>
     * if the current lookup object does not have
     * <a href="MethodHandles.Lookup.html#privacc">private access</a>.
     * The calls are made according to the following rules:
     * <ul>
     * <li><b>Step 1:</b>
     *     If {@code lookc} is not present, or if its class loader is not
     *     the same as or an ancestor of the class loader of {@code refc},
     *     then {@link SecurityManager#checkPackageAccess
     *     smgr.checkPackageAccess(refcPkg)} is called,
     *     where {@code refcPkg} is the package of {@code refc}.
     * <li><b>Step 2a:</b>
     *     If the retrieved member is not public and
     *     {@code lookc} is not present, then
     *     {@link SecurityManager#checkPermission smgr.checkPermission}
     *     with {@code RuntimePermission("accessDeclaredMembers")} is called.
     * <li><b>Step 2b:</b>
     *     If the retrieved class has a {@code null} class loader,
     *     and {@code lookc} is not present, then
     *     {@link SecurityManager#checkPermission smgr.checkPermission}
     *     with {@code RuntimePermission("getClassLoader")} is called.
     * <li><b>Step 3:</b>
     *     If the retrieved member is not public,
     *     and if {@code lookc} is not present,
     *     and if {@code defc} and {@code refc} are different,
     *     then {@link SecurityManager#checkPackageAccess
     *     smgr.checkPackageAccess(defcPkg)} is called,
     *     where {@code defcPkg} is the package of {@code defc}.
     * </ul>
     * Security checks are performed after other access checks have passed.
     * Therefore, the above rules presuppose a member or class that is public,
     * or else that is being accessed from a lookup class that has
     * rights to access the member or class.
     *
     * <h1><a name="callsens"></a>Caller sensitive methods</h1>
     * A small number of Java methods have a special property called caller sensitivity.
     * A <em>caller-sensitive</em> method can behave differently depending on the
     * identity of its immediate caller.
     * <p>
     * If a method handle for a caller-sensitive method is requested,
     * the general rules for <a href="MethodHandles.Lookup.html#equiv">bytecode behaviors</a> apply,
     * but they take account of the lookup class in a special way.
     * The resulting method handle behaves as if it were called
     * from an instruction contained in the lookup class,
     * so that the caller-sensitive method detects the lookup class.
     * (By contrast, the invoker of the method handle is disregarded.)
     * Thus, in the case of caller-sensitive methods,
     * different lookup classes may give rise to
     * differently behaving method handles.
     * <p>
     * In cases where the lookup object is
     * {@link MethodHandles#publicLookup() publicLookup()},
     * or some other lookup object without
     * <a href="MethodHandles.Lookup.html#privacc">private access</a>,
     * the lookup class is disregarded.
     * In such cases, no caller-sensitive method handle can be created,
     * access is forbidden, and the lookup fails with an
     * {@code IllegalAccessException}.
     * <p style="font-size:smaller;">
     * <em>Discussion:</em>
     * For example, the caller-sensitive method
     * {@link java.lang.Class#forName(String) Class.forName(x)}
     * can return varying classes or throw varying exceptions,
     * depending on the class loader of the class that calls it.
     * A public lookup of {@code Class.forName} will fail, because
     * there is no reasonable way to determine its bytecode behavior.
     * <p style="font-size:smaller;">
     * If an application caches method handles for broad sharing,
     * it should use {@code publicLookup()} to create them.
     * If there is a lookup of {@code Class.forName}, it will fail,
     * and the application must take appropriate action in that case.
     * It may be that a later lookup, perhaps during the invocation of a
     * bootstrap method, can incorporate the specific identity
     * of the caller, making the method accessible.
     * <p style="font-size:smaller;">
     * The function {@code MethodHandles.lookup} is caller sensitive
     * so that there can be a secure foundation for lookups.
     * Nearly all other methods in the JSR 292 API rely on lookup
     * objects to check access requests.
     */
    public static final
    class Lookup {
        /** The class on behalf of whom the lookup is being performed. */
        private final Class<?> lookupClass;

        /** The allowed sorts of members which may be looked up (PUBLIC, etc.). */
        private final int allowedModes;

        /** A single-bit mask representing {@code public} access,
         *  which may contribute to the result of {@link #lookupModes lookupModes}.
         *  The value, {@code 0x01}, happens to be the same as the value of the
         *  {@code public} {@linkplain java.lang.reflect.Modifier#PUBLIC modifier bit}.
         */
        public static final int PUBLIC = Modifier.PUBLIC;

        /** A single-bit mask representing {@code private} access,
         *  which may contribute to the result of {@link #lookupModes lookupModes}.
         *  The value, {@code 0x02}, happens to be the same as the value of the
         *  {@code private} {@linkplain java.lang.reflect.Modifier#PRIVATE modifier bit}.
         */
        public static final int PRIVATE = Modifier.PRIVATE;

        /** A single-bit mask representing {@code protected} access,
         *  which may contribute to the result of {@link #lookupModes lookupModes}.
         *  The value, {@code 0x04}, happens to be the same as the value of the
         *  {@code protected} {@linkplain java.lang.reflect.Modifier#PROTECTED modifier bit}.
         */
        public static final int PROTECTED = Modifier.PROTECTED;

        /** A single-bit mask representing {@code package} access (default access),
         *  which may contribute to the result of {@link #lookupModes lookupModes}.
         *  The value is {@code 0x08}, which does not correspond meaningfully to
         *  any particular {@linkplain java.lang.reflect.Modifier modifier bit}.
         */
        public static final int PACKAGE = Modifier.STATIC;

        /** A single-bit mask representing {@code module} access (default access),
         *  which may contribute to the result of {@link #lookupModes lookupModes}.
         *  The value is {@code 0x10}, which does not correspond meaningfully to
         *  any particular {@linkplain java.lang.reflect.Modifier modifier bit}.
         *  In conjunction with the {@code PUBLIC} modifier bit, a {@code Lookup}
         *  with this lookup mode can access all public types in the module of the
         *  lookup class and public types in packages exported by other modules
         *  to the module of the lookup class.
         *  @since 9
         */
        public static final int MODULE = PACKAGE << 1;

        private static final int ALL_MODES = (PUBLIC | PRIVATE | PROTECTED | PACKAGE | MODULE);
        private static final int TRUSTED   = -1;

        private static int fixmods(int mods) {
            mods &= (ALL_MODES - PACKAGE - MODULE);
            return (mods != 0) ? mods : (PACKAGE | MODULE);
        }

        /** Tells which class is performing the lookup.  It is this class against
         *  which checks are performed for visibility and access permissions.
         *  <p>
         *  The class implies a maximum level of access permission,
         *  but the permissions may be additionally limited by the bitmask
         *  {@link #lookupModes lookupModes}, which controls whether non-public members
         *  can be accessed.
         *  @return the lookup class, on behalf of which this lookup object finds members
         */
        public Class<?> lookupClass() {
            return lookupClass;
        }

        // This is just for calling out to MethodHandleImpl.
        private Class<?> lookupClassOrNull() {
            return (allowedModes == TRUSTED) ? null : lookupClass;
        }

        /** Tells which access-protection classes of members this lookup object can produce.
         *  The result is a bit-mask of the bits
         *  {@linkplain #PUBLIC PUBLIC (0x01)},
         *  {@linkplain #PRIVATE PRIVATE (0x02)},
         *  {@linkplain #PROTECTED PROTECTED (0x04)},
         *  {@linkplain #PACKAGE PACKAGE (0x08)},
         *  and {@linkplain #MODULE MODULE (0x10)}.
         *  <p>
         *  A freshly-created lookup object
         *  on the {@linkplain java.lang.invoke.MethodHandles#lookup() caller's class}
         *  has all possible bits set, since the caller class can access all its own members,
         *  all public types in the caller's module, and all public types in packages exported
         *  by other modules to the caller's module.
         *  A lookup object on a new lookup class
         *  {@linkplain java.lang.invoke.MethodHandles.Lookup#in created from a previous lookup object}
         *  may have some mode bits set to zero.
         *  The purpose of this is to restrict access via the new lookup object,
         *  so that it can access only names which can be reached by the original
         *  lookup object, and also by the new lookup class.
         *  @return the lookup modes, which limit the kinds of access performed by this lookup object
         */
        public int lookupModes() {
            return allowedModes & ALL_MODES;
        }

        /** Embody the current class (the lookupClass) as a lookup class
         * for method handle creation.
         * Must be called by from a method in this package,
         * which in turn is called by a method not in this package.
         */
        Lookup(Class<?> lookupClass) {
            this(lookupClass, ALL_MODES);
            // make sure we haven't accidentally picked up a privileged class:
            checkUnprivilegedlookupClass(lookupClass, ALL_MODES);
        }

        private Lookup(Class<?> lookupClass, int allowedModes) {
            this.lookupClass = lookupClass;
            this.allowedModes = allowedModes;
        }

        /**
         * Creates a lookup on the specified new lookup class.
         * The resulting object will report the specified
         * class as its own {@link #lookupClass lookupClass}.
         * <p>
         * However, the resulting {@code Lookup} object is guaranteed
         * to have no more access capabilities than the original.
         * In particular, access capabilities can be lost as follows:<ul>
         * <li>If the lookup class for this {@code Lookup} is not in a named module,
         * and the new lookup class is in a named module {@code M}, then no members in
         * {@code M}'s non-exported packages will be accessible.
         * <li>If the lookup for this {@code Lookup} is in a named module, and the
         * new lookup class is in a different module {@code M}, then no members, not even
         * public members in {@code M}'s exported packages, will be accessible.
         * <li>If the new lookup class differs from the old one,
         * protected members will not be accessible by virtue of inheritance.
         * (Protected members may continue to be accessible because of package sharing.)
         * <li>If the new lookup class is in a different package
         * than the old one, protected and default (package) members will not be accessible.
         * <li>If the new lookup class is not within the same package member
         * as the old one, private members will not be accessible.
         * <li>If the new lookup class is not accessible to the old lookup class,
         * then no members, not even public members, will be accessible.
         * (In all other cases, public members will continue to be accessible.)
         * </ul>
         * <p>
         * The resulting lookup's capabilities for loading classes
         * (used during {@link #findClass} invocations)
         * are determined by the lookup class' loader,
         * which may change due to this operation.
         *
         * @param requestedLookupClass the desired lookup class for the new lookup object
         * @return a lookup object which reports the desired lookup class
         * @throws NullPointerException if the argument is null
         */
        public Lookup in(Class<?> requestedLookupClass) {
            Objects.requireNonNull(requestedLookupClass);
            if (allowedModes == TRUSTED)  // IMPL_LOOKUP can make any lookup at all
                return new Lookup(requestedLookupClass, ALL_MODES);
            if (requestedLookupClass == this.lookupClass)
                return this;  // keep same capabilities

            int newModes = (allowedModes & (ALL_MODES & ~PROTECTED));
            if (!VerifyAccess.isSameModule(this.lookupClass, requestedLookupClass)) {
                // Allowed to teleport from an unnamed to a named module but resulting
                // Lookup has no access to module private members
                if (this.lookupClass.getModule().isNamed()) {
                    newModes = 0;
                } else {
                    newModes &= ~MODULE;
                }
            }
            if ((newModes & PACKAGE) != 0
                && !VerifyAccess.isSamePackage(this.lookupClass, requestedLookupClass)) {
                newModes &= ~(PACKAGE|PRIVATE);
            }
            // Allow nestmate lookups to be created without special privilege:
            if ((newModes & PRIVATE) != 0
                && !VerifyAccess.isSamePackageMember(this.lookupClass, requestedLookupClass)) {
                newModes &= ~PRIVATE;
            }
            if ((newModes & PUBLIC) != 0
                && !VerifyAccess.isClassAccessible(requestedLookupClass, this.lookupClass, allowedModes)) {
                // The requested class it not accessible from the lookup class.
                // No permissions.
                newModes = 0;
            }

            checkUnprivilegedlookupClass(requestedLookupClass, newModes);
            return new Lookup(requestedLookupClass, newModes);
        }

        // Make sure outer class is initialized first.
        static { IMPL_NAMES.getClass(); }

        /** Package-private version of lookup which is trusted. */
        static final Lookup IMPL_LOOKUP = new Lookup(Object.class, TRUSTED);

        private static void checkUnprivilegedlookupClass(Class<?> lookupClass, int allowedModes) {
            String name = lookupClass.getName();
            if (name.startsWith("java.lang.invoke."))
                throw newIllegalArgumentException("illegal lookupClass: "+lookupClass);

            // For caller-sensitive MethodHandles.lookup() disallow lookup from
            // restricted packages.  This a fragile and blunt approach.
            // TODO replace with a more formal and less fragile mechanism
            // that does not bluntly restrict classes under packages within
            // java.base from looking up MethodHandles or VarHandles.
            if (allowedModes == ALL_MODES && lookupClass.getClassLoader() == null) {
                if ((name.startsWith("java.") && !name.startsWith("java.util.concurrent.")) ||
                        (name.startsWith("sun.") && !name.startsWith("sun.invoke."))) {
                    throw newIllegalArgumentException("illegal lookupClass: " + lookupClass);
                }
            }
        }

        /**
         * Displays the name of the class from which lookups are to be made.
         * (The name is the one reported by {@link java.lang.Class#getName() Class.getName}.)
         * If there are restrictions on the access permitted to this lookup,
         * this is indicated by adding a suffix to the class name, consisting
         * of a slash and a keyword.  The keyword represents the strongest
         * allowed access, and is chosen as follows:
         * <ul>
         * <li>If no access is allowed, the suffix is "/noaccess".
         * <li>If only public access to types in exported packages is allowed, the suffix is "/public".
         * <li>If only public and module access are allowed, the suffix is "/module".
         * <li>If only public, module and package access are allowed, the suffix is "/package".
         * <li>If only public, module, package, and private access are allowed, the suffix is "/private".
         * </ul>
         * If none of the above cases apply, it is the case that full
         * access (public, module, package, private, and protected) is allowed.
         * In this case, no suffix is added.
         * This is true only of an object obtained originally from
         * {@link java.lang.invoke.MethodHandles#lookup MethodHandles.lookup}.
         * Objects created by {@link java.lang.invoke.MethodHandles.Lookup#in Lookup.in}
         * always have restricted access, and will display a suffix.
         * <p>
         * (It may seem strange that protected access should be
         * stronger than private access.  Viewed independently from
         * package access, protected access is the first to be lost,
         * because it requires a direct subclass relationship between
         * caller and callee.)
         * @see #in
         */
        @Override
        public String toString() {
            String cname = lookupClass.getName();
            switch (allowedModes) {
            case 0:  // no privileges
                return cname + "/noaccess";
            case PUBLIC:
                return cname + "/public";
            case PUBLIC|MODULE:
                return cname + "/module";
            case PUBLIC|MODULE|PACKAGE:
                return cname + "/package";
            case ALL_MODES & ~PROTECTED:
                return cname + "/private";
            case ALL_MODES:
                return cname;
            case TRUSTED:
                return "/trusted";  // internal only; not exported
            default:  // Should not happen, but it's a bitfield...
                cname = cname + "/" + Integer.toHexString(allowedModes);
                assert(false) : cname;
                return cname;
            }
        }

        /**
         * Produces a method handle for a static method.
         * The type of the method handle will be that of the method.
         * (Since static methods do not take receivers, there is no
         * additional receiver argument inserted into the method handle type,
         * as there would be with {@link #findVirtual findVirtual} or {@link #findSpecial findSpecial}.)
         * The method and all its argument types must be accessible to the lookup object.
         * <p>
         * The returned method handle will have
         * {@linkplain MethodHandle#asVarargsCollector variable arity} if and only if
         * the method's variable arity modifier bit ({@code 0x0080}) is set.
         * <p>
         * If the returned method handle is invoked, the method's class will
         * be initialized, if it has not already been initialized.
         * <p><b>Example:</b>
         * <blockquote><pre>{@code
import static java.lang.invoke.MethodHandles.*;
import static java.lang.invoke.MethodType.*;
...
MethodHandle MH_asList = publicLookup().findStatic(Arrays.class,
  "asList", methodType(List.class, Object[].class));
assertEquals("[x, y]", MH_asList.invoke("x", "y").toString());
         * }</pre></blockquote>
         * @param refc the class from which the method is accessed
         * @param name the name of the method
         * @param type the type of the method
         * @return the desired method handle
         * @throws NoSuchMethodException if the method does not exist
         * @throws IllegalAccessException if access checking fails,
         *                                or if the method is not {@code static},
         *                                or if the method's variable arity modifier bit
         *                                is set and {@code asVarargsCollector} fails
         * @exception SecurityException if a security manager is present and it
         *                              <a href="MethodHandles.Lookup.html#secmgr">refuses access</a>
         * @throws NullPointerException if any argument is null
         */
        public
        MethodHandle findStatic(Class<?> refc, String name, MethodType type) throws NoSuchMethodException, IllegalAccessException {
            MemberName method = resolveOrFail(REF_invokeStatic, refc, name, type);
            return getDirectMethod(REF_invokeStatic, refc, method, findBoundCallerClass(method));
        }

        /**
         * Produces a method handle for a virtual method.
         * The type of the method handle will be that of the method,
         * with the receiver type (usually {@code refc}) prepended.
         * The method and all its argument types must be accessible to the lookup object.
         * <p>
         * When called, the handle will treat the first argument as a receiver
         * and dispatch on the receiver's type to determine which method
         * implementation to enter.
         * (The dispatching action is identical with that performed by an
         * {@code invokevirtual} or {@code invokeinterface} instruction.)
         * <p>
         * The first argument will be of type {@code refc} if the lookup
         * class has full privileges to access the member.  Otherwise
         * the member must be {@code protected} and the first argument
         * will be restricted in type to the lookup class.
         * <p>
         * The returned method handle will have
         * {@linkplain MethodHandle#asVarargsCollector variable arity} if and only if
         * the method's variable arity modifier bit ({@code 0x0080}) is set.
         * <p>
         * Because of the general <a href="MethodHandles.Lookup.html#equiv">equivalence</a> between {@code invokevirtual}
         * instructions and method handles produced by {@code findVirtual},
         * if the class is {@code MethodHandle} and the name string is
         * {@code invokeExact} or {@code invoke}, the resulting
         * method handle is equivalent to one produced by
         * {@link java.lang.invoke.MethodHandles#exactInvoker MethodHandles.exactInvoker} or
         * {@link java.lang.invoke.MethodHandles#invoker MethodHandles.invoker}
         * with the same {@code type} argument.
         * <p>
         * If the class is {@code VarHandle} and the name string corresponds to
         * the name of a signature-polymorphic access mode method, the resulting
         * method handle is equivalent to one produced by
         * {@link java.lang.invoke.MethodHandles#varHandleInvoker} with
         * the access mode corresponding to the name string and with the same
         * {@code type} arguments.
         * <p>
         * <b>Example:</b>
         * <blockquote><pre>{@code
import static java.lang.invoke.MethodHandles.*;
import static java.lang.invoke.MethodType.*;
...
MethodHandle MH_concat = publicLookup().findVirtual(String.class,
  "concat", methodType(String.class, String.class));
MethodHandle MH_hashCode = publicLookup().findVirtual(Object.class,
  "hashCode", methodType(int.class));
MethodHandle MH_hashCode_String = publicLookup().findVirtual(String.class,
  "hashCode", methodType(int.class));
assertEquals("xy", (String) MH_concat.invokeExact("x", "y"));
assertEquals("xy".hashCode(), (int) MH_hashCode.invokeExact((Object)"xy"));
assertEquals("xy".hashCode(), (int) MH_hashCode_String.invokeExact("xy"));
// interface method:
MethodHandle MH_subSequence = publicLookup().findVirtual(CharSequence.class,
  "subSequence", methodType(CharSequence.class, int.class, int.class));
assertEquals("def", MH_subSequence.invoke("abcdefghi", 3, 6).toString());
// constructor "internal method" must be accessed differently:
MethodType MT_newString = methodType(void.class); //()V for new String()
try { assertEquals("impossible", lookup()
        .findVirtual(String.class, "<init>", MT_newString));
 } catch (NoSuchMethodException ex) { } // OK
MethodHandle MH_newString = publicLookup()
  .findConstructor(String.class, MT_newString);
assertEquals("", (String) MH_newString.invokeExact());
         * }</pre></blockquote>
         *
         * @param refc the class or interface from which the method is accessed
         * @param name the name of the method
         * @param type the type of the method, with the receiver argument omitted
         * @return the desired method handle
         * @throws NoSuchMethodException if the method does not exist
         * @throws IllegalAccessException if access checking fails,
         *                                or if the method is {@code static},
         *                                or if the method is {@code private} method of interface,
         *                                or if the method's variable arity modifier bit
         *                                is set and {@code asVarargsCollector} fails
         * @exception SecurityException if a security manager is present and it
         *                              <a href="MethodHandles.Lookup.html#secmgr">refuses access</a>
         * @throws NullPointerException if any argument is null
         */
        public MethodHandle findVirtual(Class<?> refc, String name, MethodType type) throws NoSuchMethodException, IllegalAccessException {
            if (refc == MethodHandle.class) {
                MethodHandle mh = findVirtualForMH(name, type);
                if (mh != null)  return mh;
            } else if (refc == VarHandle.class) {
                MethodHandle mh = findVirtualForVH(name, type);
                if (mh != null)  return mh;
            }
            byte refKind = (refc.isInterface() ? REF_invokeInterface : REF_invokeVirtual);
            MemberName method = resolveOrFail(refKind, refc, name, type);
            return getDirectMethod(refKind, refc, method, findBoundCallerClass(method));
        }
        private MethodHandle findVirtualForMH(String name, MethodType type) {
            // these names require special lookups because of the implicit MethodType argument
            if ("invoke".equals(name))
                return invoker(type);
            if ("invokeExact".equals(name))
                return exactInvoker(type);
            assert(!MemberName.isMethodHandleInvokeName(name));
            return null;
        }
        private MethodHandle findVirtualForVH(String name, MethodType type) {
            try {
                return varHandleInvoker(VarHandle.AccessMode.valueFromMethodName(name), type);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

        /**
         * Produces a method handle which creates an object and initializes it, using
         * the constructor of the specified type.
         * The parameter types of the method handle will be those of the constructor,
         * while the return type will be a reference to the constructor's class.
         * The constructor and all its argument types must be accessible to the lookup object.
         * <p>
         * The requested type must have a return type of {@code void}.
         * (This is consistent with the JVM's treatment of constructor type descriptors.)
         * <p>
         * The returned method handle will have
         * {@linkplain MethodHandle#asVarargsCollector variable arity} if and only if
         * the constructor's variable arity modifier bit ({@code 0x0080}) is set.
         * <p>
         * If the returned method handle is invoked, the constructor's class will
         * be initialized, if it has not already been initialized.
         * <p><b>Example:</b>
         * <blockquote><pre>{@code
import static java.lang.invoke.MethodHandles.*;
import static java.lang.invoke.MethodType.*;
...
MethodHandle MH_newArrayList = publicLookup().findConstructor(
  ArrayList.class, methodType(void.class, Collection.class));
Collection orig = Arrays.asList("x", "y");
Collection copy = (ArrayList) MH_newArrayList.invokeExact(orig);
assert(orig != copy);
assertEquals(orig, copy);
// a variable-arity constructor:
MethodHandle MH_newProcessBuilder = publicLookup().findConstructor(
  ProcessBuilder.class, methodType(void.class, String[].class));
ProcessBuilder pb = (ProcessBuilder)
  MH_newProcessBuilder.invoke("x", "y", "z");
assertEquals("[x, y, z]", pb.command().toString());
         * }</pre></blockquote>
         * @param refc the class or interface from which the method is accessed
         * @param type the type of the method, with the receiver argument omitted, and a void return type
         * @return the desired method handle
         * @throws NoSuchMethodException if the constructor does not exist
         * @throws IllegalAccessException if access checking fails
         *                                or if the method's variable arity modifier bit
         *                                is set and {@code asVarargsCollector} fails
         * @exception SecurityException if a security manager is present and it
         *                              <a href="MethodHandles.Lookup.html#secmgr">refuses access</a>
         * @throws NullPointerException if any argument is null
         */
        public MethodHandle findConstructor(Class<?> refc, MethodType type) throws NoSuchMethodException, IllegalAccessException {
            if (refc.isArray()) {
                throw new NoSuchMethodException("no constructor for array class: " + refc.getName());
            }
            String name = "<init>";
            MemberName ctor = resolveOrFail(REF_newInvokeSpecial, refc, name, type);
            return getDirectConstructor(refc, ctor);
        }

        /**
         * Looks up a class by name from the lookup context defined by this {@code Lookup} object. The static
         * initializer of the class is not run.
         * <p>
         * The lookup context here is determined by the {@linkplain #lookupClass() lookup class}, its class
         * loader, and the {@linkplain #lookupModes() lookup modes}. In particular, the method first attempts to
         * load the requested class, and then determines whether the class is accessible to this lookup object.
         *
         * @param targetName the fully qualified name of the class to be looked up.
         * @return the requested class.
         * @exception SecurityException if a security manager is present and it
         *            <a href="MethodHandles.Lookup.html#secmgr">refuses access</a>
         * @throws LinkageError if the linkage fails
         * @throws ClassNotFoundException if the class cannot be loaded by the lookup class' loader.
         * @throws IllegalAccessException if the class is not accessible, using the allowed access
         * modes.
         * @exception SecurityException if a security manager is present and it
         *                              <a href="MethodHandles.Lookup.html#secmgr">refuses access</a>
         * @since 9
         */
        public Class<?> findClass(String targetName) throws ClassNotFoundException, IllegalAccessException {
            Class<?> targetClass = Class.forName(targetName, false, lookupClass.getClassLoader());
            return accessClass(targetClass);
        }

        /**
         * Determines if a class can be accessed from the lookup context defined by this {@code Lookup} object. The
         * static initializer of the class is not run.
         * <p>
         * The lookup context here is determined by the {@linkplain #lookupClass() lookup class} and the
         * {@linkplain #lookupModes() lookup modes}.
         *
         * @param targetClass the class to be access-checked
         *
         * @return the class that has been access-checked
         *
         * @throws IllegalAccessException if the class is not accessible from the lookup class, using the allowed access
         * modes.
         * @exception SecurityException if a security manager is present and it
         *                              <a href="MethodHandles.Lookup.html#secmgr">refuses access</a>
         * @since 9
         */
        public Class<?> accessClass(Class<?> targetClass) throws IllegalAccessException {
            if (!VerifyAccess.isClassAccessible(targetClass, lookupClass, allowedModes)) {
                throw new MemberName(targetClass).makeAccessException("access violation", this);
            }
            checkSecurityManager(targetClass, null);
            return targetClass;
        }

        /**
         * Produces an early-bound method handle for a virtual method.
         * It will bypass checks for overriding methods on the receiver,
         * <a href="MethodHandles.Lookup.html#equiv">as if called</a> from an {@code invokespecial}
         * instruction from within the explicitly specified {@code specialCaller}.
         * The type of the method handle will be that of the method,
         * with a suitably restricted receiver type prepended.
         * (The receiver type will be {@code specialCaller} or a subtype.)
         * The method and all its argument types must be accessible
         * to the lookup object.
         * <p>
         * Before method resolution,
         * if the explicitly specified caller class is not identical with the
         * lookup class, or if this lookup object does not have
         * <a href="MethodHandles.Lookup.html#privacc">private access</a>
         * privileges, the access fails.
         * <p>
         * The returned method handle will have
         * {@linkplain MethodHandle#asVarargsCollector variable arity} if and only if
         * the method's variable arity modifier bit ({@code 0x0080}) is set.
         * <p style="font-size:smaller;">
         * <em>(Note:  JVM internal methods named {@code "<init>"} are not visible to this API,
         * even though the {@code invokespecial} instruction can refer to them
         * in special circumstances.  Use {@link #findConstructor findConstructor}
         * to access instance initialization methods in a safe manner.)</em>
         * <p><b>Example:</b>
         * <blockquote><pre>{@code
import static java.lang.invoke.MethodHandles.*;
import static java.lang.invoke.MethodType.*;
...
static class Listie extends ArrayList {
  public String toString() { return "[wee Listie]"; }
  static Lookup lookup() { return MethodHandles.lookup(); }
}
...
// no access to constructor via invokeSpecial:
MethodHandle MH_newListie = Listie.lookup()
  .findConstructor(Listie.class, methodType(void.class));
Listie l = (Listie) MH_newListie.invokeExact();
try { assertEquals("impossible", Listie.lookup().findSpecial(
        Listie.class, "<init>", methodType(void.class), Listie.class));
 } catch (NoSuchMethodException ex) { } // OK
// access to super and self methods via invokeSpecial:
MethodHandle MH_super = Listie.lookup().findSpecial(
  ArrayList.class, "toString" , methodType(String.class), Listie.class);
MethodHandle MH_this = Listie.lookup().findSpecial(
  Listie.class, "toString" , methodType(String.class), Listie.class);
MethodHandle MH_duper = Listie.lookup().findSpecial(
  Object.class, "toString" , methodType(String.class), Listie.class);
assertEquals("[]", (String) MH_super.invokeExact(l));
assertEquals(""+l, (String) MH_this.invokeExact(l));
assertEquals("[]", (String) MH_duper.invokeExact(l)); // ArrayList method
try { assertEquals("inaccessible", Listie.lookup().findSpecial(
        String.class, "toString", methodType(String.class), Listie.class));
 } catch (IllegalAccessException ex) { } // OK
Listie subl = new Listie() { public String toString() { return "[subclass]"; } };
assertEquals(""+l, (String) MH_this.invokeExact(subl)); // Listie method
         * }</pre></blockquote>
         *
         * @param refc the class or interface from which the method is accessed
         * @param name the name of the method (which must not be "&lt;init&gt;")
         * @param type the type of the method, with the receiver argument omitted
         * @param specialCaller the proposed calling class to perform the {@code invokespecial}
         * @return the desired method handle
         * @throws NoSuchMethodException if the method does not exist
         * @throws IllegalAccessException if access checking fails,
         *                                or if the method is {@code static},
         *                                or if the method's variable arity modifier bit
         *                                is set and {@code asVarargsCollector} fails
         * @exception SecurityException if a security manager is present and it
         *                              <a href="MethodHandles.Lookup.html#secmgr">refuses access</a>
         * @throws NullPointerException if any argument is null
         */
        public MethodHandle findSpecial(Class<?> refc, String name, MethodType type,
                                        Class<?> specialCaller) throws NoSuchMethodException, IllegalAccessException {
            checkSpecialCaller(specialCaller, refc);
            Lookup specialLookup = this.in(specialCaller);
            MemberName method = specialLookup.resolveOrFail(REF_invokeSpecial, refc, name, type);
            return specialLookup.getDirectMethod(REF_invokeSpecial, refc, method, findBoundCallerClass(method));
        }

        /**
         * Produces a method handle giving read access to a non-static field.
         * The type of the method handle will have a return type of the field's
         * value type.
         * The method handle's single argument will be the instance containing
         * the field.
         * Access checking is performed immediately on behalf of the lookup class.
         * @param refc the class or interface from which the method is accessed
         * @param name the field's name
         * @param type the field's type
         * @return a method handle which can load values from the field
         * @throws NoSuchFieldException if the field does not exist
         * @throws IllegalAccessException if access checking fails, or if the field is {@code static}
         * @exception SecurityException if a security manager is present and it
         *                              <a href="MethodHandles.Lookup.html#secmgr">refuses access</a>
         * @throws NullPointerException if any argument is null
         * @see #findVarHandle(Class, String, Class)
         */
        public MethodHandle findGetter(Class<?> refc, String name, Class<?> type) throws NoSuchFieldException, IllegalAccessException {
            MemberName field = resolveOrFail(REF_getField, refc, name, type);
            return getDirectField(REF_getField, refc, field);
        }

        /**
         * Produces a method handle giving write access to a non-static field.
         * The type of the method handle will have a void return type.
         * The method handle will take two arguments, the instance containing
         * the field, and the value to be stored.
         * The second argument will be of the field's value type.
         * Access checking is performed immediately on behalf of the lookup class.
         * @param refc the class or interface from which the method is accessed
         * @param name the field's name
         * @param type the field's type
         * @return a method handle which can store values into the field
         * @throws NoSuchFieldException if the field does not exist
         * @throws IllegalAccessException if access checking fails, or if the field is {@code static}
         * @exception SecurityException if a security manager is present and it
         *                              <a href="MethodHandles.Lookup.html#secmgr">refuses access</a>
         * @throws NullPointerException if any argument is null
         * @see #findVarHandle(Class, String, Class)
         */
        public MethodHandle findSetter(Class<?> refc, String name, Class<?> type) throws NoSuchFieldException, IllegalAccessException {
            MemberName field = resolveOrFail(REF_putField, refc, name, type);
            return getDirectField(REF_putField, refc, field);
        }

        /**
         * Produces a VarHandle giving access to non-static fields of type
         * {@code T} declared by a receiver class of type {@code R}, supporting
         * shape {@code (R : T)}.
         * <p>
         * Access checking is performed immediately on behalf of the lookup
         * class.
         * <p>
         * Certain access modes of the returned VarHandle are unsupported under
         * the following conditions:
         * <ul>
         * <li>if the field is declared {@code final}, then the write, atomic
         *     update, numeric atomic update, and bitwise atomic update access
         *     modes are unsupported.
         * <li>if the field type is anything other than {@code byte},
         *     {@code short}, {@code char}, {@code int}, {@code long},
         *     {@code float}, or {@code double} then numeric atomic update
         *     access modes are unsupported.
         * <li>if the field type is anything other than {@code boolean},
         *     {@code byte}, {@code short}, {@code char}, {@code int} or
         *     {@code long} then bitwise atomic update access modes are
         *     unsupported.
         * </ul>
         * <p>
         * If the field is declared {@code volatile} then the returned VarHandle
         * will override access to the field (effectively ignore the
         * {@code volatile} declaration) in accordance to it's specified
         * access modes.
         * <p>
         * If the field type is {@code float} or {@code double} then numeric
         * and atomic update access modes compare values using their bitwise
         * representation (see {@link Float#floatToRawIntBits} and
         * {@link Double#doubleToRawLongBits}, respectively).
         * @apiNote
         * Bitwise comparison of {@code float} values or {@code double} values,
         * as performed by the numeric and atomic update access modes, differ
         * from the primitive {@code ==} operator and the {@link Float#equals}
         * and {@link Double#equals} methods, specifically with respect to
         * comparing NaN values or comparing {@code -0.0} with {@code +0.0}.
         * Care should be taken when performing a compare and set or a compare
         * and exchange operation with such values since the operation may
         * unexpectedly fail.
         * There are many possible NaN values that are considered to be
         * {@code NaN} in Java, although no IEEE 754 floating-point operation
         * provided by Java can distinguish between them.  Operation failure can
         * occur if the expected or witness value is a NaN value and it is
         * transformed (perhaps in a platform specific manner) into another NaN
         * value, and thus has a different bitwise representation (see
         * {@link Float#intBitsToFloat} or {@link Double#longBitsToDouble} for more
         * details).
         * The values {@code -0.0} and {@code +0.0} have different bitwise
         * representations but are considered equal when using the primitive
         * {@code ==} operator.  Operation failure can occur if, for example, a
         * numeric algorithm computes an expected value to be say {@code -0.0}
         * and previously computed the witness value to be say {@code +0.0}.
         * @param recv the receiver class, of type {@code R}, that declares the
         * non-static field
         * @param name the field's name
         * @param type the field's type, of type {@code T}
         * @return a VarHandle giving access to non-static fields.
         * @throws NoSuchFieldException if the field does not exist
         * @throws IllegalAccessException if access checking fails, or if the field is {@code static}
         * @exception SecurityException if a security manager is present and it
         *                              <a href="MethodHandles.Lookup.html#secmgr">refuses access</a>
         * @throws NullPointerException if any argument is null
         * @since 9
         */
        public VarHandle findVarHandle(Class<?> recv, String name, Class<?> type) throws NoSuchFieldException, IllegalAccessException {
            MemberName getField = resolveOrFail(REF_getField, recv, name, type);
            MemberName putField = resolveOrFail(REF_putField, recv, name, type);
            return getFieldVarHandle(REF_getField, REF_putField, recv, getField, putField);
        }

        /**
         * Produces a method handle giving read access to a static field.
         * The type of the method handle will have a return type of the field's
         * value type.
         * The method handle will take no arguments.
         * Access checking is performed immediately on behalf of the lookup class.
         * <p>
         * If the returned method handle is invoked, the field's class will
         * be initialized, if it has not already been initialized.
         * @param refc the class or interface from which the method is accessed
         * @param name the field's name
         * @param type the field's type
         * @return a method handle which can load values from the field
         * @throws NoSuchFieldException if the field does not exist
         * @throws IllegalAccessException if access checking fails, or if the field is not {@code static}
         * @exception SecurityException if a security manager is present and it
         *                              <a href="MethodHandles.Lookup.html#secmgr">refuses access</a>
         * @throws NullPointerException if any argument is null
         */
        public MethodHandle findStaticGetter(Class<?> refc, String name, Class<?> type) throws NoSuchFieldException, IllegalAccessException {
            MemberName field = resolveOrFail(REF_getStatic, refc, name, type);
            return getDirectField(REF_getStatic, refc, field);
        }

        /**
         * Produces a method handle giving write access to a static field.
         * The type of the method handle will have a void return type.
         * The method handle will take a single
         * argument, of the field's value type, the value to be stored.
         * Access checking is performed immediately on behalf of the lookup class.
         * <p>
         * If the returned method handle is invoked, the field's class will
         * be initialized, if it has not already been initialized.
         * @param refc the class or interface from which the method is accessed
         * @param name the field's name
         * @param type the field's type
         * @return a method handle which can store values into the field
         * @throws NoSuchFieldException if the field does not exist
         * @throws IllegalAccessException if access checking fails, or if the field is not {@code static}
         * @exception SecurityException if a security manager is present and it
         *                              <a href="MethodHandles.Lookup.html#secmgr">refuses access</a>
         * @throws NullPointerException if any argument is null
         */
        public MethodHandle findStaticSetter(Class<?> refc, String name, Class<?> type) throws NoSuchFieldException, IllegalAccessException {
            MemberName field = resolveOrFail(REF_putStatic, refc, name, type);
            return getDirectField(REF_putStatic, refc, field);
        }

        /**
         * Produces a VarHandle giving access to a static field of type
         * {@code T} declared by a given declaring class, supporting shape
         * {@code ((empty) : T)}.
         * <p>
         * Access checking is performed immediately on behalf of the lookup
         * class.
         * <p>
         * If the returned VarHandle is operated on, the declaring class will be
         * initialized, if it has not already been initialized.
         * <p>
         * Certain access modes of the returned VarHandle are unsupported under
         * the following conditions:
         * <ul>
         * <li>if the field is declared {@code final}, then the write, atomic
         *     update, numeric atomic update, and bitwise atomic update access
         *     modes are unsupported.
         * <li>if the field type is anything other than {@code byte},
         *     {@code short}, {@code char}, {@code int}, {@code long},
         *     {@code float}, or {@code double}, then numeric atomic update
         *     access modes are unsupported.
         * <li>if the field type is anything other than {@code boolean},
         *     {@code byte}, {@code short}, {@code char}, {@code int} or
         *     {@code long} then bitwise atomic update access modes are
         *     unsupported.
         * </ul>
         * <p>
         * If the field is declared {@code volatile} then the returned VarHandle
         * will override access to the field (effectively ignore the
         * {@code volatile} declaration) in accordance to it's specified
         * access modes.
         * <p>
         * If the field type is {@code float} or {@code double} then numeric
         * and atomic update access modes compare values using their bitwise
         * representation (see {@link Float#floatToRawIntBits} and
         * {@link Double#doubleToRawLongBits}, respectively).
         * @apiNote
         * Bitwise comparison of {@code float} values or {@code double} values,
         * as performed by the numeric and atomic update access modes, differ
         * from the primitive {@code ==} operator and the {@link Float#equals}
         * and {@link Double#equals} methods, specifically with respect to
         * comparing NaN values or comparing {@code -0.0} with {@code +0.0}.
         * Care should be taken when performing a compare and set or a compare
         * and exchange operation with such values since the operation may
         * unexpectedly fail.
         * There are many possible NaN values that are considered to be
         * {@code NaN} in Java, although no IEEE 754 floating-point operation
         * provided by Java can distinguish between them.  Operation failure can
         * occur if the expected or witness value is a NaN value and it is
         * transformed (perhaps in a platform specific manner) into another NaN
         * value, and thus has a different bitwise representation (see
         * {@link Float#intBitsToFloat} or {@link Double#longBitsToDouble} for more
         * details).
         * The values {@code -0.0} and {@code +0.0} have different bitwise
         * representations but are considered equal when using the primitive
         * {@code ==} operator.  Operation failure can occur if, for example, a
         * numeric algorithm computes an expected value to be say {@code -0.0}
         * and previously computed the witness value to be say {@code +0.0}.
         * @param decl the class that declares the static field
         * @param name the field's name
         * @param type the field's type, of type {@code T}
         * @return a VarHandle giving access to a static field
         * @throws NoSuchFieldException if the field does not exist
         * @throws IllegalAccessException if access checking fails, or if the field is not {@code static}
         * @exception SecurityException if a security manager is present and it
         *                              <a href="MethodHandles.Lookup.html#secmgr">refuses access</a>
         * @throws NullPointerException if any argument is null
         * @since 9
         */
        public VarHandle findStaticVarHandle(Class<?> decl, String name, Class<?> type) throws NoSuchFieldException, IllegalAccessException {
            MemberName getField = resolveOrFail(REF_getStatic, decl, name, type);
            MemberName putField = resolveOrFail(REF_putStatic, decl, name, type);
            return getFieldVarHandle(REF_getStatic, REF_putStatic, decl, getField, putField);
        }

        /**
         * Produces an early-bound method handle for a non-static method.
         * The receiver must have a supertype {@code defc} in which a method
         * of the given name and type is accessible to the lookup class.
         * The method and all its argument types must be accessible to the lookup object.
         * The type of the method handle will be that of the method,
         * without any insertion of an additional receiver parameter.
         * The given receiver will be bound into the method handle,
         * so that every call to the method handle will invoke the
         * requested method on the given receiver.
         * <p>
         * The returned method handle will have
         * {@linkplain MethodHandle#asVarargsCollector variable arity} if and only if
         * the method's variable arity modifier bit ({@code 0x0080}) is set
         * <em>and</em> the trailing array argument is not the only argument.
         * (If the trailing array argument is the only argument,
         * the given receiver value will be bound to it.)
         * <p>
         * This is equivalent to the following code:
         * <blockquote><pre>{@code
import static java.lang.invoke.MethodHandles.*;
import static java.lang.invoke.MethodType.*;
...
MethodHandle mh0 = lookup().findVirtual(defc, name, type);
MethodHandle mh1 = mh0.bindTo(receiver);
mh1 = mh1.withVarargs(mh0.isVarargsCollector());
return mh1;
         * }</pre></blockquote>
         * where {@code defc} is either {@code receiver.getClass()} or a super
         * type of that class, in which the requested method is accessible
         * to the lookup class.
         * (Note that {@code bindTo} does not preserve variable arity.)
         * @param receiver the object from which the method is accessed
         * @param name the name of the method
         * @param type the type of the method, with the receiver argument omitted
         * @return the desired method handle
         * @throws NoSuchMethodException if the method does not exist
         * @throws IllegalAccessException if access checking fails
         *                                or if the method's variable arity modifier bit
         *                                is set and {@code asVarargsCollector} fails
         * @exception SecurityException if a security manager is present and it
         *                              <a href="MethodHandles.Lookup.html#secmgr">refuses access</a>
         * @throws NullPointerException if any argument is null
         * @see MethodHandle#bindTo
         * @see #findVirtual
         */
        public MethodHandle bind(Object receiver, String name, MethodType type) throws NoSuchMethodException, IllegalAccessException {
            Class<? extends Object> refc = receiver.getClass(); // may get NPE
            MemberName method = resolveOrFail(REF_invokeSpecial, refc, name, type);
            MethodHandle mh = getDirectMethodNoRestrict(REF_invokeSpecial, refc, method, findBoundCallerClass(method));
            return mh.bindArgumentL(0, receiver).setVarargs(method);
        }

        /**
         * Makes a <a href="MethodHandleInfo.html#directmh">direct method handle</a>
         * to <i>m</i>, if the lookup class has permission.
         * If <i>m</i> is non-static, the receiver argument is treated as an initial argument.
         * If <i>m</i> is virtual, overriding is respected on every call.
         * Unlike the Core Reflection API, exceptions are <em>not</em> wrapped.
         * The type of the method handle will be that of the method,
         * with the receiver type prepended (but only if it is non-static).
         * If the method's {@code accessible} flag is not set,
         * access checking is performed immediately on behalf of the lookup class.
         * If <i>m</i> is not public, do not share the resulting handle with untrusted parties.
         * <p>
         * The returned method handle will have
         * {@linkplain MethodHandle#asVarargsCollector variable arity} if and only if
         * the method's variable arity modifier bit ({@code 0x0080}) is set.
         * <p>
         * If <i>m</i> is static, and
         * if the returned method handle is invoked, the method's class will
         * be initialized, if it has not already been initialized.
         * @param m the reflected method
         * @return a method handle which can invoke the reflected method
         * @throws IllegalAccessException if access checking fails
         *                                or if the method's variable arity modifier bit
         *                                is set and {@code asVarargsCollector} fails
         * @throws NullPointerException if the argument is null
         */
        public MethodHandle unreflect(Method m) throws IllegalAccessException {
            if (m.getDeclaringClass() == MethodHandle.class) {
                MethodHandle mh = unreflectForMH(m);
                if (mh != null)  return mh;
            }
            if (m.getDeclaringClass() == VarHandle.class) {
                MethodHandle mh = unreflectForVH(m);
                if (mh != null)  return mh;
            }
            MemberName method = new MemberName(m);
            byte refKind = method.getReferenceKind();
            if (refKind == REF_invokeSpecial)
                refKind = REF_invokeVirtual;
            assert(method.isMethod());
            Lookup lookup = m.isAccessible() ? IMPL_LOOKUP : this;
            return lookup.getDirectMethodNoSecurityManager(refKind, method.getDeclaringClass(), method, findBoundCallerClass(method));
        }
        private MethodHandle unreflectForMH(Method m) {
            // these names require special lookups because they throw UnsupportedOperationException
            if (MemberName.isMethodHandleInvokeName(m.getName()))
                return MethodHandleImpl.fakeMethodHandleInvoke(new MemberName(m));
            return null;
        }
        private MethodHandle unreflectForVH(Method m) {
            // these names require special lookups because they throw UnsupportedOperationException
            if (MemberName.isVarHandleMethodInvokeName(m.getName()))
                return MethodHandleImpl.fakeVarHandleInvoke(new MemberName(m));
            return null;
        }

        /**
         * Produces a method handle for a reflected method.
         * It will bypass checks for overriding methods on the receiver,
         * <a href="MethodHandles.Lookup.html#equiv">as if called</a> from an {@code invokespecial}
         * instruction from within the explicitly specified {@code specialCaller}.
         * The type of the method handle will be that of the method,
         * with a suitably restricted receiver type prepended.
         * (The receiver type will be {@code specialCaller} or a subtype.)
         * If the method's {@code accessible} flag is not set,
         * access checking is performed immediately on behalf of the lookup class,
         * as if {@code invokespecial} instruction were being linked.
         * <p>
         * Before method resolution,
         * if the explicitly specified caller class is not identical with the
         * lookup class, or if this lookup object does not have
         * <a href="MethodHandles.Lookup.html#privacc">private access</a>
         * privileges, the access fails.
         * <p>
         * The returned method handle will have
         * {@linkplain MethodHandle#asVarargsCollector variable arity} if and only if
         * the method's variable arity modifier bit ({@code 0x0080}) is set.
         * @param m the reflected method
         * @param specialCaller the class nominally calling the method
         * @return a method handle which can invoke the reflected method
         * @throws IllegalAccessException if access checking fails,
         *                                or if the method is {@code static},
         *                                or if the method's variable arity modifier bit
         *                                is set and {@code asVarargsCollector} fails
         * @throws NullPointerException if any argument is null
         */
        public MethodHandle unreflectSpecial(Method m, Class<?> specialCaller) throws IllegalAccessException {
            checkSpecialCaller(specialCaller, null);
            Lookup specialLookup = this.in(specialCaller);
            MemberName method = new MemberName(m, true);
            assert(method.isMethod());
            // ignore m.isAccessible:  this is a new kind of access
            return specialLookup.getDirectMethodNoSecurityManager(REF_invokeSpecial, method.getDeclaringClass(), method, findBoundCallerClass(method));
        }

        /**
         * Produces a method handle for a reflected constructor.
         * The type of the method handle will be that of the constructor,
         * with the return type changed to the declaring class.
         * The method handle will perform a {@code newInstance} operation,
         * creating a new instance of the constructor's class on the
         * arguments passed to the method handle.
         * <p>
         * If the constructor's {@code accessible} flag is not set,
         * access checking is performed immediately on behalf of the lookup class.
         * <p>
         * The returned method handle will have
         * {@linkplain MethodHandle#asVarargsCollector variable arity} if and only if
         * the constructor's variable arity modifier bit ({@code 0x0080}) is set.
         * <p>
         * If the returned method handle is invoked, the constructor's class will
         * be initialized, if it has not already been initialized.
         * @param c the reflected constructor
         * @return a method handle which can invoke the reflected constructor
         * @throws IllegalAccessException if access checking fails
         *                                or if the method's variable arity modifier bit
         *                                is set and {@code asVarargsCollector} fails
         * @throws NullPointerException if the argument is null
         */
        public MethodHandle unreflectConstructor(Constructor<?> c) throws IllegalAccessException {
            MemberName ctor = new MemberName(c);
            assert(ctor.isConstructor());
            Lookup lookup = c.isAccessible() ? IMPL_LOOKUP : this;
            return lookup.getDirectConstructorNoSecurityManager(ctor.getDeclaringClass(), ctor);
        }

        /**
         * Produces a method handle giving read access to a reflected field.
         * The type of the method handle will have a return type of the field's
         * value type.
         * If the field is static, the method handle will take no arguments.
         * Otherwise, its single argument will be the instance containing
         * the field.
         * If the field's {@code accessible} flag is not set,
         * access checking is performed immediately on behalf of the lookup class.
         * <p>
         * If the field is static, and
         * if the returned method handle is invoked, the field's class will
         * be initialized, if it has not already been initialized.
         * @param f the reflected field
         * @return a method handle which can load values from the reflected field
         * @throws IllegalAccessException if access checking fails
         * @throws NullPointerException if the argument is null
         */
        public MethodHandle unreflectGetter(Field f) throws IllegalAccessException {
            return unreflectField(f, false);
        }
        private MethodHandle unreflectField(Field f, boolean isSetter) throws IllegalAccessException {
            MemberName field = new MemberName(f, isSetter);
            assert(isSetter
                    ? MethodHandleNatives.refKindIsSetter(field.getReferenceKind())
                    : MethodHandleNatives.refKindIsGetter(field.getReferenceKind()));
            Lookup lookup = f.isAccessible() ? IMPL_LOOKUP : this;
            return lookup.getDirectFieldNoSecurityManager(field.getReferenceKind(), f.getDeclaringClass(), field);
        }

        /**
         * Produces a method handle giving write access to a reflected field.
         * The type of the method handle will have a void return type.
         * If the field is static, the method handle will take a single
         * argument, of the field's value type, the value to be stored.
         * Otherwise, the two arguments will be the instance containing
         * the field, and the value to be stored.
         * If the field's {@code accessible} flag is not set,
         * access checking is performed immediately on behalf of the lookup class.
         * <p>
         * If the field is static, and
         * if the returned method handle is invoked, the field's class will
         * be initialized, if it has not already been initialized.
         * @param f the reflected field
         * @return a method handle which can store values into the reflected field
         * @throws IllegalAccessException if access checking fails
         * @throws NullPointerException if the argument is null
         */
        public MethodHandle unreflectSetter(Field f) throws IllegalAccessException {
            return unreflectField(f, true);
        }

        /**
         * Produces a VarHandle that accesses fields of type {@code T} declared
         * by a class of type {@code R}, as described by the given reflected
         * field.
         * If the field is non-static the VarHandle supports a shape of
         * {@code (R : T)}, otherwise supports a shape of {@code ((empty) : T)}.
         * <p>
         * Access checking is performed immediately on behalf of the lookup
         * class, regardless of the value of the field's {@code accessible}
         * flag.
         * <p>
         * If the field is static, and if the returned VarHandle is operated
         * on, the field's declaring class will be initialized, if it has not
         * already been initialized.
         * <p>
         * Certain access modes of the returned VarHandle are unsupported under
         * the following conditions:
         * <ul>
         * <li>if the field is declared {@code final}, then the write, atomic
         *     update, numeric atomic update, and bitwise atomic update access
         *     modes are unsupported.
         * <li>if the field type is anything other than {@code byte},
         *     {@code short}, {@code char}, {@code int}, {@code long},
         *     {@code float}, or {@code double} then numeric atomic update
         *     access modes are unsupported.
         * <li>if the field type is anything other than {@code boolean},
         *     {@code byte}, {@code short}, {@code char}, {@code int} or
         *     {@code long} then bitwise atomic update access modes are
         *     unsupported.
         * </ul>
         * <p>
         * If the field is declared {@code volatile} then the returned VarHandle
         * will override access to the field (effectively ignore the
         * {@code volatile} declaration) in accordance to it's specified
         * access modes.
         * <p>
         * If the field type is {@code float} or {@code double} then numeric
         * and atomic update access modes compare values using their bitwise
         * representation (see {@link Float#floatToRawIntBits} and
         * {@link Double#doubleToRawLongBits}, respectively).
         * @apiNote
         * Bitwise comparison of {@code float} values or {@code double} values,
         * as performed by the numeric and atomic update access modes, differ
         * from the primitive {@code ==} operator and the {@link Float#equals}
         * and {@link Double#equals} methods, specifically with respect to
         * comparing NaN values or comparing {@code -0.0} with {@code +0.0}.
         * Care should be taken when performing a compare and set or a compare
         * and exchange operation with such values since the operation may
         * unexpectedly fail.
         * There are many possible NaN values that are considered to be
         * {@code NaN} in Java, although no IEEE 754 floating-point operation
         * provided by Java can distinguish between them.  Operation failure can
         * occur if the expected or witness value is a NaN value and it is
         * transformed (perhaps in a platform specific manner) into another NaN
         * value, and thus has a different bitwise representation (see
         * {@link Float#intBitsToFloat} or {@link Double#longBitsToDouble} for more
         * details).
         * The values {@code -0.0} and {@code +0.0} have different bitwise
         * representations but are considered equal when using the primitive
         * {@code ==} operator.  Operation failure can occur if, for example, a
         * numeric algorithm computes an expected value to be say {@code -0.0}
         * and previously computed the witness value to be say {@code +0.0}.
         * @param f the reflected field, with a field of type {@code T}, and
         * a declaring class of type {@code R}
         * @return a VarHandle giving access to non-static fields or a static
         * field
         * @throws IllegalAccessException if access checking fails
         * @throws NullPointerException if the argument is null
         * @since 9
         */
        public VarHandle unreflectVarHandle(Field f) throws IllegalAccessException {
            MemberName getField = new MemberName(f, false);
            MemberName putField = new MemberName(f, true);
            return getFieldVarHandleNoSecurityManager(getField.getReferenceKind(), putField.getReferenceKind(),
                                                      f.getDeclaringClass(), getField, putField);
        }

        /**
         * Cracks a <a href="MethodHandleInfo.html#directmh">direct method handle</a>
         * created by this lookup object or a similar one.
         * Security and access checks are performed to ensure that this lookup object
         * is capable of reproducing the target method handle.
         * This means that the cracking may fail if target is a direct method handle
         * but was created by an unrelated lookup object.
         * This can happen if the method handle is <a href="MethodHandles.Lookup.html#callsens">caller sensitive</a>
         * and was created by a lookup object for a different class.
         * @param target a direct method handle to crack into symbolic reference components
         * @return a symbolic reference which can be used to reconstruct this method handle from this lookup object
         * @exception SecurityException if a security manager is present and it
         *                              <a href="MethodHandles.Lookup.html#secmgr">refuses access</a>
         * @throws IllegalArgumentException if the target is not a direct method handle or if access checking fails
         * @exception NullPointerException if the target is {@code null}
         * @see MethodHandleInfo
         * @since 1.8
         */
        public MethodHandleInfo revealDirect(MethodHandle target) {
            MemberName member = target.internalMemberName();
            if (member == null || (!member.isResolved() &&
                                   !member.isMethodHandleInvoke() &&
                                   !member.isVarHandleMethodInvoke()))
                throw newIllegalArgumentException("not a direct method handle");
            Class<?> defc = member.getDeclaringClass();
            byte refKind = member.getReferenceKind();
            assert(MethodHandleNatives.refKindIsValid(refKind));
            if (refKind == REF_invokeSpecial && !target.isInvokeSpecial())
                // Devirtualized method invocation is usually formally virtual.
                // To avoid creating extra MemberName objects for this common case,
                // we encode this extra degree of freedom using MH.isInvokeSpecial.
                refKind = REF_invokeVirtual;
            if (refKind == REF_invokeVirtual && defc.isInterface())
                // Symbolic reference is through interface but resolves to Object method (toString, etc.)
                refKind = REF_invokeInterface;
            // Check SM permissions and member access before cracking.
            try {
                checkAccess(refKind, defc, member);
                checkSecurityManager(defc, member);
            } catch (IllegalAccessException ex) {
                throw new IllegalArgumentException(ex);
            }
            if (allowedModes != TRUSTED && member.isCallerSensitive()) {
                Class<?> callerClass = target.internalCallerClass();
                if (!hasPrivateAccess() || callerClass != lookupClass())
                    throw new IllegalArgumentException("method handle is caller sensitive: "+callerClass);
            }
            // Produce the handle to the results.
            return new InfoFromMemberName(this, member, refKind);
        }

        /// Helper methods, all package-private.

        MemberName resolveOrFail(byte refKind, Class<?> refc, String name, Class<?> type) throws NoSuchFieldException, IllegalAccessException {
            checkSymbolicClass(refc);  // do this before attempting to resolve
            Objects.requireNonNull(name);
            Objects.requireNonNull(type);
            return IMPL_NAMES.resolveOrFail(refKind, new MemberName(refc, name, type, refKind), lookupClassOrNull(),
                                            NoSuchFieldException.class);
        }

        MemberName resolveOrFail(byte refKind, Class<?> refc, String name, MethodType type) throws NoSuchMethodException, IllegalAccessException {
            checkSymbolicClass(refc);  // do this before attempting to resolve
            Objects.requireNonNull(name);
            Objects.requireNonNull(type);
            checkMethodName(refKind, name);  // NPE check on name
            return IMPL_NAMES.resolveOrFail(refKind, new MemberName(refc, name, type, refKind), lookupClassOrNull(),
                                            NoSuchMethodException.class);
        }

        MemberName resolveOrFail(byte refKind, MemberName member) throws ReflectiveOperationException {
            checkSymbolicClass(member.getDeclaringClass());  // do this before attempting to resolve
            Objects.requireNonNull(member.getName());
            Objects.requireNonNull(member.getType());
            return IMPL_NAMES.resolveOrFail(refKind, member, lookupClassOrNull(),
                                            ReflectiveOperationException.class);
        }

        void checkSymbolicClass(Class<?> refc) throws IllegalAccessException {
            Objects.requireNonNull(refc);
            Class<?> caller = lookupClassOrNull();
            if (caller != null && !VerifyAccess.isClassAccessible(refc, caller, allowedModes))
                throw new MemberName(refc).makeAccessException("symbolic reference class is not accessible", this);
        }

        /** Check name for an illegal leading "&lt;" character. */
        void checkMethodName(byte refKind, String name) throws NoSuchMethodException {
            if (name.startsWith("<") && refKind != REF_newInvokeSpecial)
                throw new NoSuchMethodException("illegal method name: "+name);
        }


        /**
         * Find my trustable caller class if m is a caller sensitive method.
         * If this lookup object has private access, then the caller class is the lookupClass.
         * Otherwise, if m is caller-sensitive, throw IllegalAccessException.
         */
        Class<?> findBoundCallerClass(MemberName m) throws IllegalAccessException {
            Class<?> callerClass = null;
            if (MethodHandleNatives.isCallerSensitive(m)) {
                // Only lookups with private access are allowed to resolve caller-sensitive methods
                if (hasPrivateAccess()) {
                    callerClass = lookupClass;
                } else {
                    throw new IllegalAccessException("Attempt to lookup caller-sensitive method using restricted lookup object");
                }
            }
            return callerClass;
        }

        private boolean hasPrivateAccess() {
            return (allowedModes & PRIVATE) != 0;
        }

        /**
         * Perform necessary <a href="MethodHandles.Lookup.html#secmgr">access checks</a>.
         * Determines a trustable caller class to compare with refc, the symbolic reference class.
         * If this lookup object has private access, then the caller class is the lookupClass.
         */
        void checkSecurityManager(Class<?> refc, MemberName m) {
            SecurityManager smgr = System.getSecurityManager();
            if (smgr == null)  return;
            if (allowedModes == TRUSTED)  return;

            // Step 1:
            boolean fullPowerLookup = hasPrivateAccess();
            if (!fullPowerLookup ||
                !VerifyAccess.classLoaderIsAncestor(lookupClass, refc)) {
                ReflectUtil.checkPackageAccess(refc);
            }

            if (m == null) {  // findClass or accessClass
                // Step 2b:
                if (!fullPowerLookup) {
                    smgr.checkPermission(SecurityConstants.GET_CLASSLOADER_PERMISSION);
                }
                return;
            }

            // Step 2a:
            if (m.isPublic()) return;
            if (!fullPowerLookup) {
                smgr.checkPermission(SecurityConstants.CHECK_MEMBER_ACCESS_PERMISSION);
            }

            // Step 3:
            Class<?> defc = m.getDeclaringClass();
            if (!fullPowerLookup && defc != refc) {
                ReflectUtil.checkPackageAccess(defc);
            }
        }

        void checkMethod(byte refKind, Class<?> refc, MemberName m) throws IllegalAccessException {
            boolean wantStatic = (refKind == REF_invokeStatic);
            String message;
            if (m.isConstructor())
                message = "expected a method, not a constructor";
            else if (!m.isMethod())
                message = "expected a method";
            else if (wantStatic != m.isStatic())
                message = wantStatic ? "expected a static method" : "expected a non-static method";
            else
                { checkAccess(refKind, refc, m); return; }
            throw m.makeAccessException(message, this);
        }

        void checkField(byte refKind, Class<?> refc, MemberName m) throws IllegalAccessException {
            boolean wantStatic = !MethodHandleNatives.refKindHasReceiver(refKind);
            String message;
            if (wantStatic != m.isStatic())
                message = wantStatic ? "expected a static field" : "expected a non-static field";
            else
                { checkAccess(refKind, refc, m); return; }
            throw m.makeAccessException(message, this);
        }

        /** Check public/protected/private bits on the symbolic reference class and its member. */
        void checkAccess(byte refKind, Class<?> refc, MemberName m) throws IllegalAccessException {
            assert(m.referenceKindIsConsistentWith(refKind) &&
                   MethodHandleNatives.refKindIsValid(refKind) &&
                   (MethodHandleNatives.refKindIsField(refKind) == m.isField()));
            int allowedModes = this.allowedModes;
            if (allowedModes == TRUSTED)  return;
            int mods = m.getModifiers();
            if (Modifier.isProtected(mods) &&
                    refKind == REF_invokeVirtual &&
                    m.getDeclaringClass() == Object.class &&
                    m.getName().equals("clone") &&
                    refc.isArray()) {
                // The JVM does this hack also.
                // (See ClassVerifier::verify_invoke_instructions
                // and LinkResolver::check_method_accessability.)
                // Because the JVM does not allow separate methods on array types,
                // there is no separate method for int[].clone.
                // All arrays simply inherit Object.clone.
                // But for access checking logic, we make Object.clone
                // (normally protected) appear to be public.
                // Later on, when the DirectMethodHandle is created,
                // its leading argument will be restricted to the
                // requested array type.
                // N.B. The return type is not adjusted, because
                // that is *not* the bytecode behavior.
                mods ^= Modifier.PROTECTED | Modifier.PUBLIC;
            }
            if (Modifier.isProtected(mods) && refKind == REF_newInvokeSpecial) {
                // cannot "new" a protected ctor in a different package
                mods ^= Modifier.PROTECTED;
            }
            if (Modifier.isFinal(mods) &&
                    MethodHandleNatives.refKindIsSetter(refKind))
                throw m.makeAccessException("unexpected set of a final field", this);
            int requestedModes = fixmods(mods);  // adjust 0 => PACKAGE
            if ((requestedModes & allowedModes) != 0) {
                if (VerifyAccess.isMemberAccessible(refc, m.getDeclaringClass(),
                                                    mods, lookupClass(), allowedModes))
                    return;
            } else {
                // Protected members can also be checked as if they were package-private.
                if ((requestedModes & PROTECTED) != 0 && (allowedModes & PACKAGE) != 0
                        && VerifyAccess.isSamePackage(m.getDeclaringClass(), lookupClass()))
                    return;
            }
            throw m.makeAccessException(accessFailedMessage(refc, m), this);
        }

        String accessFailedMessage(Class<?> refc, MemberName m) {
            Class<?> defc = m.getDeclaringClass();
            int mods = m.getModifiers();
            // check the class first:
            boolean classOK = (Modifier.isPublic(defc.getModifiers()) &&
                               (defc == refc ||
                                Modifier.isPublic(refc.getModifiers())));
            if (!classOK && (allowedModes & PACKAGE) != 0) {
                classOK = (VerifyAccess.isClassAccessible(defc, lookupClass(), ALL_MODES) &&
                           (defc == refc ||
                            VerifyAccess.isClassAccessible(refc, lookupClass(), ALL_MODES)));
            }
            if (!classOK)
                return "class is not public";
            if (Modifier.isPublic(mods))
                return "access to public member failed";  // (how?, module not readable?)
            if (Modifier.isPrivate(mods))
                return "member is private";
            if (Modifier.isProtected(mods))
                return "member is protected";
            return "member is private to package";
        }

        private static final boolean ALLOW_NESTMATE_ACCESS = false;

        private void checkSpecialCaller(Class<?> specialCaller, Class<?> refc) throws IllegalAccessException {
            int allowedModes = this.allowedModes;
            if (allowedModes == TRUSTED)  return;
            if (!hasPrivateAccess()
                || (specialCaller != lookupClass()
                       // ensure non-abstract methods in superinterfaces can be special-invoked
                    && !(refc != null && refc.isInterface() && refc.isAssignableFrom(specialCaller))
                    && !(ALLOW_NESTMATE_ACCESS &&
                         VerifyAccess.isSamePackageMember(specialCaller, lookupClass()))))
                throw new MemberName(specialCaller).
                    makeAccessException("no private access for invokespecial", this);
        }

        private boolean restrictProtectedReceiver(MemberName method) {
            // The accessing class only has the right to use a protected member
            // on itself or a subclass.  Enforce that restriction, from JVMS 5.4.4, etc.
            if (!method.isProtected() || method.isStatic()
                || allowedModes == TRUSTED
                || method.getDeclaringClass() == lookupClass()
                || VerifyAccess.isSamePackage(method.getDeclaringClass(), lookupClass())
                || (ALLOW_NESTMATE_ACCESS &&
                    VerifyAccess.isSamePackageMember(method.getDeclaringClass(), lookupClass())))
                return false;
            return true;
        }
        private MethodHandle restrictReceiver(MemberName method, DirectMethodHandle mh, Class<?> caller) throws IllegalAccessException {
            assert(!method.isStatic());
            // receiver type of mh is too wide; narrow to caller
            if (!method.getDeclaringClass().isAssignableFrom(caller)) {
                throw method.makeAccessException("caller class must be a subclass below the method", caller);
            }
            MethodType rawType = mh.type();
            if (rawType.parameterType(0) == caller)  return mh;
            MethodType narrowType = rawType.changeParameterType(0, caller);
            assert(!mh.isVarargsCollector());  // viewAsType will lose varargs-ness
            assert(mh.viewAsTypeChecks(narrowType, true));
            return mh.copyWith(narrowType, mh.form);
        }

        /** Check access and get the requested method. */
        private MethodHandle getDirectMethod(byte refKind, Class<?> refc, MemberName method, Class<?> callerClass) throws IllegalAccessException {
            final boolean doRestrict    = true;
            final boolean checkSecurity = true;
            return getDirectMethodCommon(refKind, refc, method, checkSecurity, doRestrict, callerClass);
        }
        /** Check access and get the requested method, eliding receiver narrowing rules. */
        private MethodHandle getDirectMethodNoRestrict(byte refKind, Class<?> refc, MemberName method, Class<?> callerClass) throws IllegalAccessException {
            final boolean doRestrict    = false;
            final boolean checkSecurity = true;
            return getDirectMethodCommon(refKind, refc, method, checkSecurity, doRestrict, callerClass);
        }
        /** Check access and get the requested method, eliding security manager checks. */
        private MethodHandle getDirectMethodNoSecurityManager(byte refKind, Class<?> refc, MemberName method, Class<?> callerClass) throws IllegalAccessException {
            final boolean doRestrict    = true;
            final boolean checkSecurity = false;  // not needed for reflection or for linking CONSTANT_MH constants
            return getDirectMethodCommon(refKind, refc, method, checkSecurity, doRestrict, callerClass);
        }
        /** Common code for all methods; do not call directly except from immediately above. */
        private MethodHandle getDirectMethodCommon(byte refKind, Class<?> refc, MemberName method,
                                                   boolean checkSecurity,
                                                   boolean doRestrict, Class<?> callerClass) throws IllegalAccessException {
            checkMethod(refKind, refc, method);
            // Optionally check with the security manager; this isn't needed for unreflect* calls.
            if (checkSecurity)
                checkSecurityManager(refc, method);
            assert(!method.isMethodHandleInvoke());

            if (refKind == REF_invokeSpecial &&
                refc != lookupClass() &&
                !refc.isInterface() &&
                refc != lookupClass().getSuperclass() &&
                refc.isAssignableFrom(lookupClass())) {
                assert(!method.getName().equals("<init>"));  // not this code path
                // Per JVMS 6.5, desc. of invokespecial instruction:
                // If the method is in a superclass of the LC,
                // and if our original search was above LC.super,
                // repeat the search (symbolic lookup) from LC.super
                // and continue with the direct superclass of that class,
                // and so forth, until a match is found or no further superclasses exist.
                // FIXME: MemberName.resolve should handle this instead.
                Class<?> refcAsSuper = lookupClass();
                MemberName m2;
                do {
                    refcAsSuper = refcAsSuper.getSuperclass();
                    m2 = new MemberName(refcAsSuper,
                                        method.getName(),
                                        method.getMethodType(),
                                        REF_invokeSpecial);
                    m2 = IMPL_NAMES.resolveOrNull(refKind, m2, lookupClassOrNull());
                } while (m2 == null &&         // no method is found yet
                         refc != refcAsSuper); // search up to refc
                if (m2 == null)  throw new InternalError(method.toString());
                method = m2;
                refc = refcAsSuper;
                // redo basic checks
                checkMethod(refKind, refc, method);
            }

            DirectMethodHandle dmh = DirectMethodHandle.make(refKind, refc, method);
            MethodHandle mh = dmh;
            // Optionally narrow the receiver argument to refc using restrictReceiver.
            if (doRestrict &&
                   (refKind == REF_invokeSpecial ||
                       (MethodHandleNatives.refKindHasReceiver(refKind) &&
                           restrictProtectedReceiver(method)))) {
                mh = restrictReceiver(method, dmh, lookupClass());
            }
            mh = maybeBindCaller(method, mh, callerClass);
            mh = mh.setVarargs(method);
            return mh;
        }
        private MethodHandle maybeBindCaller(MemberName method, MethodHandle mh,
                                             Class<?> callerClass)
                                             throws IllegalAccessException {
            if (allowedModes == TRUSTED || !MethodHandleNatives.isCallerSensitive(method))
                return mh;
            Class<?> hostClass = lookupClass;
            if (!hasPrivateAccess())  // caller must have private access
                hostClass = callerClass;  // callerClass came from a security manager style stack walk
            MethodHandle cbmh = MethodHandleImpl.bindCaller(mh, hostClass);
            // Note: caller will apply varargs after this step happens.
            return cbmh;
        }
        /** Check access and get the requested field. */
        private MethodHandle getDirectField(byte refKind, Class<?> refc, MemberName field) throws IllegalAccessException {
            final boolean checkSecurity = true;
            return getDirectFieldCommon(refKind, refc, field, checkSecurity);
        }
        /** Check access and get the requested field, eliding security manager checks. */
        private MethodHandle getDirectFieldNoSecurityManager(byte refKind, Class<?> refc, MemberName field) throws IllegalAccessException {
            final boolean checkSecurity = false;  // not needed for reflection or for linking CONSTANT_MH constants
            return getDirectFieldCommon(refKind, refc, field, checkSecurity);
        }
        /** Common code for all fields; do not call directly except from immediately above. */
        private MethodHandle getDirectFieldCommon(byte refKind, Class<?> refc, MemberName field,
                                                  boolean checkSecurity) throws IllegalAccessException {
            checkField(refKind, refc, field);
            // Optionally check with the security manager; this isn't needed for unreflect* calls.
            if (checkSecurity)
                checkSecurityManager(refc, field);
            DirectMethodHandle dmh = DirectMethodHandle.make(refc, field);
            boolean doRestrict = (MethodHandleNatives.refKindHasReceiver(refKind) &&
                                    restrictProtectedReceiver(field));
            if (doRestrict)
                return restrictReceiver(field, dmh, lookupClass());
            return dmh;
        }
        private VarHandle getFieldVarHandle(byte getRefKind, byte putRefKind,
                                            Class<?> refc, MemberName getField, MemberName putField)
                throws IllegalAccessException {
            final boolean checkSecurity = true;
            return getFieldVarHandleCommon(getRefKind, putRefKind, refc, getField, putField, checkSecurity);
        }
        private VarHandle getFieldVarHandleNoSecurityManager(byte getRefKind, byte putRefKind,
                                                             Class<?> refc, MemberName getField, MemberName putField)
                throws IllegalAccessException {
            final boolean checkSecurity = false;
            return getFieldVarHandleCommon(getRefKind, putRefKind, refc, getField, putField, checkSecurity);
        }
        private VarHandle getFieldVarHandleCommon(byte getRefKind, byte putRefKind,
                                                  Class<?> refc, MemberName getField, MemberName putField,
                                                  boolean checkSecurity) throws IllegalAccessException {
            assert getField.isStatic() == putField.isStatic();
            assert getField.isGetter() && putField.isSetter();
            assert MethodHandleNatives.refKindIsStatic(getRefKind) == MethodHandleNatives.refKindIsStatic(putRefKind);
            assert MethodHandleNatives.refKindIsGetter(getRefKind) && MethodHandleNatives.refKindIsSetter(putRefKind);

            checkField(getRefKind, refc, getField);
            if (checkSecurity)
                checkSecurityManager(refc, getField);

            if (!putField.isFinal()) {
                // A VarHandle does not support updates to final fields, any
                // such VarHandle to a final field will be read-only and
                // therefore the following write-based accessibility checks are
                // only required for non-final fields
                checkField(putRefKind, refc, putField);
                if (checkSecurity)
                    checkSecurityManager(refc, putField);
            }

            boolean doRestrict = (MethodHandleNatives.refKindHasReceiver(getRefKind) &&
                                  restrictProtectedReceiver(getField));
            if (doRestrict) {
                assert !getField.isStatic();
                // receiver type of VarHandle is too wide; narrow to caller
                if (!getField.getDeclaringClass().isAssignableFrom(lookupClass())) {
                    throw getField.makeAccessException("caller class must be a subclass below the method", lookupClass());
                }
                refc = lookupClass();
            }
            return VarHandles.makeFieldHandle(getField, refc, getField.getFieldType(), this.allowedModes == TRUSTED);
        }
        /** Check access and get the requested constructor. */
        private MethodHandle getDirectConstructor(Class<?> refc, MemberName ctor) throws IllegalAccessException {
            final boolean checkSecurity = true;
            return getDirectConstructorCommon(refc, ctor, checkSecurity);
        }
        /** Check access and get the requested constructor, eliding security manager checks. */
        private MethodHandle getDirectConstructorNoSecurityManager(Class<?> refc, MemberName ctor) throws IllegalAccessException {
            final boolean checkSecurity = false;  // not needed for reflection or for linking CONSTANT_MH constants
            return getDirectConstructorCommon(refc, ctor, checkSecurity);
        }
        /** Common code for all constructors; do not call directly except from immediately above. */
        private MethodHandle getDirectConstructorCommon(Class<?> refc, MemberName ctor,
                                                  boolean checkSecurity) throws IllegalAccessException {
            assert(ctor.isConstructor());
            checkAccess(REF_newInvokeSpecial, refc, ctor);
            // Optionally check with the security manager; this isn't needed for unreflect* calls.
            if (checkSecurity)
                checkSecurityManager(refc, ctor);
            assert(!MethodHandleNatives.isCallerSensitive(ctor));  // maybeBindCaller not relevant here
            return DirectMethodHandle.make(ctor).setVarargs(ctor);
        }

        /** Hook called from the JVM (via MethodHandleNatives) to link MH constants:
         */
        /*non-public*/
        MethodHandle linkMethodHandleConstant(byte refKind, Class<?> defc, String name, Object type) throws ReflectiveOperationException {
            if (!(type instanceof Class || type instanceof MethodType))
                throw new InternalError("unresolved MemberName");
            MemberName member = new MemberName(refKind, defc, name, type);
            MethodHandle mh = LOOKASIDE_TABLE.get(member);
            if (mh != null) {
                checkSymbolicClass(defc);
                return mh;
            }
            // Treat MethodHandle.invoke and invokeExact specially.
            if (defc == MethodHandle.class && refKind == REF_invokeVirtual) {
                mh = findVirtualForMH(member.getName(), member.getMethodType());
                if (mh != null) {
                    return mh;
                }
            }
            MemberName resolved = resolveOrFail(refKind, member);
            mh = getDirectMethodForConstant(refKind, defc, resolved);
            if (mh instanceof DirectMethodHandle
                    && canBeCached(refKind, defc, resolved)) {
                MemberName key = mh.internalMemberName();
                if (key != null) {
                    key = key.asNormalOriginal();
                }
                if (member.equals(key)) {  // better safe than sorry
                    LOOKASIDE_TABLE.put(key, (DirectMethodHandle) mh);
                }
            }
            return mh;
        }
        private
        boolean canBeCached(byte refKind, Class<?> defc, MemberName member) {
            if (refKind == REF_invokeSpecial) {
                return false;
            }
            if (!Modifier.isPublic(defc.getModifiers()) ||
                    !Modifier.isPublic(member.getDeclaringClass().getModifiers()) ||
                    !member.isPublic() ||
                    member.isCallerSensitive()) {
                return false;
            }
            ClassLoader loader = defc.getClassLoader();
            if (!jdk.internal.misc.VM.isSystemDomainLoader(loader)) {
                ClassLoader sysl = ClassLoader.getSystemClassLoader();
                boolean found = false;
                while (sysl != null) {
                    if (loader == sysl) { found = true; break; }
                    sysl = sysl.getParent();
                }
                if (!found) {
                    return false;
                }
            }
            try {
                MemberName resolved2 = publicLookup().resolveOrFail(refKind,
                    new MemberName(refKind, defc, member.getName(), member.getType()));
                checkSecurityManager(defc, resolved2);
            } catch (ReflectiveOperationException | SecurityException ex) {
                return false;
            }
            return true;
        }
        private
        MethodHandle getDirectMethodForConstant(byte refKind, Class<?> defc, MemberName member)
                throws ReflectiveOperationException {
            if (MethodHandleNatives.refKindIsField(refKind)) {
                return getDirectFieldNoSecurityManager(refKind, defc, member);
            } else if (MethodHandleNatives.refKindIsMethod(refKind)) {
                return getDirectMethodNoSecurityManager(refKind, defc, member, lookupClass);
            } else if (refKind == REF_newInvokeSpecial) {
                return getDirectConstructorNoSecurityManager(defc, member);
            }
            // oops
            throw newIllegalArgumentException("bad MethodHandle constant #"+member);
        }

        static ConcurrentHashMap<MemberName, DirectMethodHandle> LOOKASIDE_TABLE = new ConcurrentHashMap<>();
    }

    /**
     * Helper class used to lazily create PUBLIC_LOOKUP with a lookup class
     * in an <em>unnamed module</em>.
     *
     * @see Lookup#publicLookup
     */
    private static class LookupHelper {
        private static final String UNNAMED = "Unnamed";
        private static final String OBJECT  = "java/lang/Object";

        private static Class<?> createClass() {
            try {
                ClassWriter cw = new ClassWriter(0);
                cw.visit(Opcodes.V1_8,
                         Opcodes.ACC_FINAL + Opcodes.ACC_SUPER,
                         UNNAMED,
                         null,
                         OBJECT,
                         null);
                cw.visitSource(UNNAMED, null);
                cw.visitEnd();
                byte[] bytes = cw.toByteArray();
                ClassLoader loader = new ClassLoader(null) {
                    @Override
                    protected Class<?> findClass(String cn) throws ClassNotFoundException {
                        if (cn.equals(UNNAMED))
                            return super.defineClass(UNNAMED, bytes, 0, bytes.length);
                        throw new ClassNotFoundException(cn);
                    }
                };
                return loader.loadClass(UNNAMED);
            } catch (Exception e) {
                throw new InternalError(e);
            }
        }

        private static final Class<?> PUBLIC_LOOKUP_CLASS = createClass();

        /**
         * Lookup that is trusted minimally. It can only be used to create
         * method handles to publicly accessible members in exported packages.
         *
         * @see MethodHandles#publicLookup
         */
        static final Lookup PUBLIC_LOOKUP = new Lookup(PUBLIC_LOOKUP_CLASS, Lookup.PUBLIC);
    }

    /**
     * Produces a method handle constructing arrays of a desired type.
     * The return type of the method handle will be the array type.
     * The type of its sole argument will be {@code int}, which specifies the size of the array.
     * @param arrayClass an array type
     * @return a method handle which can create arrays of the given type
     * @throws NullPointerException if the argument is {@code null}
     * @throws IllegalArgumentException if {@code arrayClass} is not an array type
     * @see java.lang.reflect.Array#newInstance(Class, int)
     * @since 9
     */
    public static
    MethodHandle arrayConstructor(Class<?> arrayClass) throws IllegalArgumentException {
        if (!arrayClass.isArray()) {
            throw newIllegalArgumentException("not an array class: " + arrayClass.getName());
        }
        MethodHandle ani = MethodHandleImpl.getConstantHandle(MethodHandleImpl.MH_Array_newInstance).
                bindTo(arrayClass.getComponentType());
        return ani.asType(ani.type().changeReturnType(arrayClass));
    }

    /**
     * Produces a method handle returning the length of an array.
     * The type of the method handle will have {@code int} as return type,
     * and its sole argument will be the array type.
     * @param arrayClass an array type
     * @return a method handle which can retrieve the length of an array of the given array type
     * @throws NullPointerException if the argument is {@code null}
     * @throws IllegalArgumentException if arrayClass is not an array type
     * @since 9
     */
    public static
    MethodHandle arrayLength(Class<?> arrayClass) throws IllegalArgumentException {
        return MethodHandleImpl.makeArrayElementAccessor(arrayClass, MethodHandleImpl.ArrayAccess.LENGTH);
    }

    /**
     * Produces a method handle giving read access to elements of an array.
     * The type of the method handle will have a return type of the array's
     * element type.  Its first argument will be the array type,
     * and the second will be {@code int}.
     * @param arrayClass an array type
     * @return a method handle which can load values from the given array type
     * @throws NullPointerException if the argument is null
     * @throws  IllegalArgumentException if arrayClass is not an array type
     */
    public static
    MethodHandle arrayElementGetter(Class<?> arrayClass) throws IllegalArgumentException {
        return MethodHandleImpl.makeArrayElementAccessor(arrayClass, MethodHandleImpl.ArrayAccess.GET);
    }

    /**
     * Produces a method handle giving write access to elements of an array.
     * The type of the method handle will have a void return type.
     * Its last argument will be the array's element type.
     * The first and second arguments will be the array type and int.
     * @param arrayClass the class of an array
     * @return a method handle which can store values into the array type
     * @throws NullPointerException if the argument is null
     * @throws IllegalArgumentException if arrayClass is not an array type
     */
    public static
    MethodHandle arrayElementSetter(Class<?> arrayClass) throws IllegalArgumentException {
        return MethodHandleImpl.makeArrayElementAccessor(arrayClass, MethodHandleImpl.ArrayAccess.SET);
    }

    /**
     *
     * Produces a VarHandle giving access to elements of an array type
     * {@code T[]}, supporting shape {@code (T[], int : T)}.
     * <p>
     * Certain access modes of the returned VarHandle are unsupported under
     * the following conditions:
     * <ul>
     * <li>if the component type is anything other than {@code byte},
     *     {@code short}, {@code char}, {@code int}, {@code long},
     *     {@code float}, or {@code double} then numeric atomic update access
     *     modes are unsupported.
     * <li>if the field type is anything other than {@code boolean},
     *     {@code byte}, {@code short}, {@code char}, {@code int} or
     *     {@code long} then bitwise atomic update access modes are
     *     unsupported.
     * </ul>
     * <p>
     * If the component type is {@code float} or {@code double} then numeric
     * and atomic update access modes compare values using their bitwise
     * representation (see {@link Float#floatToRawIntBits} and
     * {@link Double#doubleToRawLongBits}, respectively).
     * @apiNote
     * Bitwise comparison of {@code float} values or {@code double} values,
     * as performed by the numeric and atomic update access modes, differ
     * from the primitive {@code ==} operator and the {@link Float#equals}
     * and {@link Double#equals} methods, specifically with respect to
     * comparing NaN values or comparing {@code -0.0} with {@code +0.0}.
     * Care should be taken when performing a compare and set or a compare
     * and exchange operation with such values since the operation may
     * unexpectedly fail.
     * There are many possible NaN values that are considered to be
     * {@code NaN} in Java, although no IEEE 754 floating-point operation
     * provided by Java can distinguish between them.  Operation failure can
     * occur if the expected or witness value is a NaN value and it is
     * transformed (perhaps in a platform specific manner) into another NaN
     * value, and thus has a different bitwise representation (see
     * {@link Float#intBitsToFloat} or {@link Double#longBitsToDouble} for more
     * details).
     * The values {@code -0.0} and {@code +0.0} have different bitwise
     * representations but are considered equal when using the primitive
     * {@code ==} operator.  Operation failure can occur if, for example, a
     * numeric algorithm computes an expected value to be say {@code -0.0}
     * and previously computed the witness value to be say {@code +0.0}.
     * @param arrayClass the class of an array, of type {@code T[]}
     * @return a VarHandle giving access to elements of an array
     * @throws NullPointerException if the arrayClass is null
     * @throws IllegalArgumentException if arrayClass is not an array type
     * @since 9
     */
    public static
    VarHandle arrayElementVarHandle(Class<?> arrayClass) throws IllegalArgumentException {
        return VarHandles.makeArrayElementHandle(arrayClass);
    }

    /**
     * Produces a VarHandle giving access to elements of a {@code byte[]} array
     * viewed as if it were a different primitive array type, such as
     * {@code int[]} or {@code long[]}.  The shape of the resulting VarHandle is
     * {@code (byte[], int : T)}, where the {@code int} coordinate type
     * corresponds to an argument that is an index in a {@code byte[]} array,
     * and {@code T} is the component type of the given view array class.  The
     * returned VarHandle accesses bytes at an index in a {@code byte[]} array,
     * composing bytes to or from a value of {@code T} according to the given
     * endianness.
     * <p>
     * The supported component types (variables types) are {@code short},
     * {@code char}, {@code int}, {@code long}, {@code float} and
     * {@code double}.
     * <p>
     * Access of bytes at a given index will result in an
     * {@code IndexOutOfBoundsException} if the index is less than {@code 0}
     * or greater than the {@code byte[]} array length minus the size (in bytes)
     * of {@code T}.
     * <p>
     * Access of bytes at an index may be aligned or misaligned for {@code T},
     * with respect to the underlying memory address, {@code A} say, associated
     * with the array and index.
     * If access is misaligned then access for anything other than the
     * {@code get} and {@code set} access modes will result in an
     * {@code IllegalStateException}.  In such cases atomic access is only
     * guaranteed with respect to the largest power of two that divides the GCD
     * of {@code A} and the size (in bytes) of {@code T}.
     * If access is aligned then following access modes are supported and are
     * guaranteed to support atomic access:
     * <ul>
     * <li>read write access modes for all {@code T}, with the exception of
     *     access modes {@code get} and {@code set} for {@code long} and
     *     {@code double} on 32-bit platforms.
     * <li>atomic update access modes for {@code int}, {@code long},
     *     {@code float} or {@code double}.
     *     (Future major platform releases of the JDK may support additional
     *     types for certain currently unsupported access modes.)
     * <li>numeric atomic update access modes for {@code int} and {@code long}.
     *     (Future major platform releases of the JDK may support additional
     *     numeric types for certain currently unsupported access modes.)
     * <li>bitwise atomic update access modes for {@code int} and {@code long}.
     *     (Future major platform releases of the JDK may support additional
     *     numeric types for certain currently unsupported access modes.)
     * </ul>
     * <p>
     * Misaligned access, and therefore atomicity guarantees, may be determined
     * for {@code byte[]} arrays without operating on a specific array.  Given
     * an {@code index}, {@code T} and it's corresponding boxed type,
     * {@code T_BOX}, misalignment may be determined as follows:
     * <pre>{@code
     * int sizeOfT = T_BOX.BYTES;  // size in bytes of T
     * int misalignedAtZeroIndex = ByteBuffer.wrap(new byte[0]).
     *     alignmentOffset(0, sizeOfT);
     * int misalignedAtIndex = (misalignedAtZeroIndex + index) % sizeOfT;
     * boolean isMisaligned = misalignedAtIndex != 0;
     * }</pre>
     * <p>
     * If the variable type is {@code float} or {@code double} then atomic
     * update access modes compare values using their bitwise representation
     * (see {@link Float#floatToRawIntBits} and
     * {@link Double#doubleToRawLongBits}, respectively).
     * @param viewArrayClass the view array class, with a component type of
     * type {@code T}
     * @param byteOrder the endianness of the view array elements, as
     * stored in the underlying {@code byte} array
     * @return a VarHandle giving access to elements of a {@code byte[]} array
     * viewed as if elements corresponding to the components type of the view
     * array class
     * @throws NullPointerException if viewArrayClass or byteOrder is null
     * @throws IllegalArgumentException if viewArrayClass is not an array type
     * @throws UnsupportedOperationException if the component type of
     * viewArrayClass is not supported as a variable type
     * @since 9
     */
    public static
    VarHandle byteArrayViewVarHandle(Class<?> viewArrayClass,
                                     ByteOrder byteOrder) throws IllegalArgumentException {
        Objects.requireNonNull(byteOrder);
        return VarHandles.byteArrayViewHandle(viewArrayClass,
                                              byteOrder == ByteOrder.BIG_ENDIAN);
    }

    /**
     * Produces a VarHandle giving access to elements of a {@code ByteBuffer}
     * viewed as if it were an array of elements of a different primitive
     * component type to that of {@code byte}, such as {@code int[]} or
     * {@code long[]}.  The shape of the resulting VarHandle is
     * {@code (ByteBuffer, int : T)}, where the {@code int} coordinate type
     * corresponds to an argument that is an index in a {@code ByteBuffer}, and
     * {@code T} is the component type of the given view array class.  The
     * returned VarHandle accesses bytes at an index in a {@code ByteBuffer},
     * composing bytes to or from a value of {@code T} according to the given
     * endianness.
     * <p>
     * The supported component types (variables types) are {@code short},
     * {@code char}, {@code int}, {@code long}, {@code float} and
     * {@code double}.
     * <p>
     * Access will result in a {@code ReadOnlyBufferException} for anything
     * other than the read access modes if the {@code ByteBuffer} is read-only.
     * <p>
     * Access of bytes at a given index will result in an
     * {@code IndexOutOfBoundsException} if the index is less than {@code 0}
     * or greater than the {@code ByteBuffer} limit minus the size (in bytes) of
     * {@code T}.
     * <p>
     * Access of bytes at an index may be aligned or misaligned for {@code T},
     * with respect to the underlying memory address, {@code A} say, associated
     * with the {@code ByteBuffer} and index.
     * If access is misaligned then access for anything other than the
     * {@code get} and {@code set} access modes will result in an
     * {@code IllegalStateException}.  In such cases atomic access is only
     * guaranteed with respect to the largest power of two that divides the GCD
     * of {@code A} and the size (in bytes) of {@code T}.
     * If access is aligned then following access modes are supported and are
     * guaranteed to support atomic access:
     * <ul>
     * <li>read write access modes for all {@code T}, with the exception of
     *     access modes {@code get} and {@code set} for {@code long} and
     *     {@code double} on 32-bit platforms.
     * <li>atomic update access modes for {@code int}, {@code long},
     *     {@code float} or {@code double}.
     *     (Future major platform releases of the JDK may support additional
     *     types for certain currently unsupported access modes.)
     * <li>numeric atomic update access modes for {@code int} and {@code long}.
     *     (Future major platform releases of the JDK may support additional
     *     numeric types for certain currently unsupported access modes.)
     * <li>bitwise atomic update access modes for {@code int} and {@code long}.
     *     (Future major platform releases of the JDK may support additional
     *     numeric types for certain currently unsupported access modes.)
     * </ul>
     * <p>
     * Misaligned access, and therefore atomicity guarantees, may be determined
     * for a {@code ByteBuffer}, {@code bb} (direct or otherwise), an
     * {@code index}, {@code T} and it's corresponding boxed type,
     * {@code T_BOX}, as follows:
     * <pre>{@code
     * int sizeOfT = T_BOX.BYTES;  // size in bytes of T
     * ByteBuffer bb = ...
     * int misalignedAtIndex = bb.alignmentOffset(index, sizeOfT);
     * boolean isMisaligned = misalignedAtIndex != 0;
     * }</pre>
     * <p>
     * If the variable type is {@code float} or {@code double} then atomic
     * update access modes compare values using their bitwise representation
     * (see {@link Float#floatToRawIntBits} and
     * {@link Double#doubleToRawLongBits}, respectively).
     * @param viewArrayClass the view array class, with a component type of
     * type {@code T}
     * @param byteOrder the endianness of the view array elements, as
     * stored in the underlying {@code ByteBuffer} (Note this overrides the
     * endianness of a {@code ByteBuffer})
     * @return a VarHandle giving access to elements of a {@code ByteBuffer}
     * viewed as if elements corresponding to the components type of the view
     * array class
     * @throws NullPointerException if viewArrayClass or byteOrder is null
     * @throws IllegalArgumentException if viewArrayClass is not an array type
     * @throws UnsupportedOperationException if the component type of
     * viewArrayClass is not supported as a variable type
     * @since 9
     */
    public static
    VarHandle byteBufferViewVarHandle(Class<?> viewArrayClass,
                                      ByteOrder byteOrder) throws IllegalArgumentException {
        Objects.requireNonNull(byteOrder);
        return VarHandles.makeByteBufferViewHandle(viewArrayClass,
                                                   byteOrder == ByteOrder.BIG_ENDIAN);
    }


    /// method handle invocation (reflective style)

    /**
     * Produces a method handle which will invoke any method handle of the
     * given {@code type}, with a given number of trailing arguments replaced by
     * a single trailing {@code Object[]} array.
     * The resulting invoker will be a method handle with the following
     * arguments:
     * <ul>
     * <li>a single {@code MethodHandle} target
     * <li>zero or more leading values (counted by {@code leadingArgCount})
     * <li>an {@code Object[]} array containing trailing arguments
     * </ul>
     * <p>
     * The invoker will invoke its target like a call to {@link MethodHandle#invoke invoke} with
     * the indicated {@code type}.
     * That is, if the target is exactly of the given {@code type}, it will behave
     * like {@code invokeExact}; otherwise it behave as if {@link MethodHandle#asType asType}
     * is used to convert the target to the required {@code type}.
     * <p>
     * The type of the returned invoker will not be the given {@code type}, but rather
     * will have all parameters except the first {@code leadingArgCount}
     * replaced by a single array of type {@code Object[]}, which will be
     * the final parameter.
     * <p>
     * Before invoking its target, the invoker will spread the final array, apply
     * reference casts as necessary, and unbox and widen primitive arguments.
     * If, when the invoker is called, the supplied array argument does
     * not have the correct number of elements, the invoker will throw
     * an {@link IllegalArgumentException} instead of invoking the target.
     * <p>
     * This method is equivalent to the following code (though it may be more efficient):
     * <blockquote><pre>{@code
MethodHandle invoker = MethodHandles.invoker(type);
int spreadArgCount = type.parameterCount() - leadingArgCount;
invoker = invoker.asSpreader(Object[].class, spreadArgCount);
return invoker;
     * }</pre></blockquote>
     * This method throws no reflective or security exceptions.
     * @param type the desired target type
     * @param leadingArgCount number of fixed arguments, to be passed unchanged to the target
     * @return a method handle suitable for invoking any method handle of the given type
     * @throws NullPointerException if {@code type} is null
     * @throws IllegalArgumentException if {@code leadingArgCount} is not in
     *                  the range from 0 to {@code type.parameterCount()} inclusive,
     *                  or if the resulting method handle's type would have
     *          <a href="MethodHandle.html#maxarity">too many parameters</a>
     */
    public static
    MethodHandle spreadInvoker(MethodType type, int leadingArgCount) {
        if (leadingArgCount < 0 || leadingArgCount > type.parameterCount())
            throw newIllegalArgumentException("bad argument count", leadingArgCount);
        type = type.asSpreaderType(Object[].class, leadingArgCount, type.parameterCount() - leadingArgCount);
        return type.invokers().spreadInvoker(leadingArgCount);
    }

    /**
     * Produces a special <em>invoker method handle</em> which can be used to
     * invoke any method handle of the given type, as if by {@link MethodHandle#invokeExact invokeExact}.
     * The resulting invoker will have a type which is
     * exactly equal to the desired type, except that it will accept
     * an additional leading argument of type {@code MethodHandle}.
     * <p>
     * This method is equivalent to the following code (though it may be more efficient):
     * {@code publicLookup().findVirtual(MethodHandle.class, "invokeExact", type)}
     *
     * <p style="font-size:smaller;">
     * <em>Discussion:</em>
     * Invoker method handles can be useful when working with variable method handles
     * of unknown types.
     * For example, to emulate an {@code invokeExact} call to a variable method
     * handle {@code M}, extract its type {@code T},
     * look up the invoker method {@code X} for {@code T},
     * and call the invoker method, as {@code X.invoke(T, A...)}.
     * (It would not work to call {@code X.invokeExact}, since the type {@code T}
     * is unknown.)
     * If spreading, collecting, or other argument transformations are required,
     * they can be applied once to the invoker {@code X} and reused on many {@code M}
     * method handle values, as long as they are compatible with the type of {@code X}.
     * <p style="font-size:smaller;">
     * <em>(Note:  The invoker method is not available via the Core Reflection API.
     * An attempt to call {@linkplain java.lang.reflect.Method#invoke java.lang.reflect.Method.invoke}
     * on the declared {@code invokeExact} or {@code invoke} method will raise an
     * {@link java.lang.UnsupportedOperationException UnsupportedOperationException}.)</em>
     * <p>
     * This method throws no reflective or security exceptions.
     * @param type the desired target type
     * @return a method handle suitable for invoking any method handle of the given type
     * @throws IllegalArgumentException if the resulting method handle's type would have
     *          <a href="MethodHandle.html#maxarity">too many parameters</a>
     */
    public static
    MethodHandle exactInvoker(MethodType type) {
        return type.invokers().exactInvoker();
    }

    /**
     * Produces a special <em>invoker method handle</em> which can be used to
     * invoke any method handle compatible with the given type, as if by {@link MethodHandle#invoke invoke}.
     * The resulting invoker will have a type which is
     * exactly equal to the desired type, except that it will accept
     * an additional leading argument of type {@code MethodHandle}.
     * <p>
     * Before invoking its target, if the target differs from the expected type,
     * the invoker will apply reference casts as
     * necessary and box, unbox, or widen primitive values, as if by {@link MethodHandle#asType asType}.
     * Similarly, the return value will be converted as necessary.
     * If the target is a {@linkplain MethodHandle#asVarargsCollector variable arity method handle},
     * the required arity conversion will be made, again as if by {@link MethodHandle#asType asType}.
     * <p>
     * This method is equivalent to the following code (though it may be more efficient):
     * {@code publicLookup().findVirtual(MethodHandle.class, "invoke", type)}
     * <p style="font-size:smaller;">
     * <em>Discussion:</em>
     * A {@linkplain MethodType#genericMethodType general method type} is one which
     * mentions only {@code Object} arguments and return values.
     * An invoker for such a type is capable of calling any method handle
     * of the same arity as the general type.
     * <p style="font-size:smaller;">
     * <em>(Note:  The invoker method is not available via the Core Reflection API.
     * An attempt to call {@linkplain java.lang.reflect.Method#invoke java.lang.reflect.Method.invoke}
     * on the declared {@code invokeExact} or {@code invoke} method will raise an
     * {@link java.lang.UnsupportedOperationException UnsupportedOperationException}.)</em>
     * <p>
     * This method throws no reflective or security exceptions.
     * @param type the desired target type
     * @return a method handle suitable for invoking any method handle convertible to the given type
     * @throws IllegalArgumentException if the resulting method handle's type would have
     *          <a href="MethodHandle.html#maxarity">too many parameters</a>
     */
    public static
    MethodHandle invoker(MethodType type) {
        return type.invokers().genericInvoker();
    }

    /**
     * Produces a special <em>invoker method handle</em> which can be used to
     * invoke a signature-polymorphic access mode method on any VarHandle whose
     * associated access mode type is compatible with the given type.
     * The resulting invoker will have a type which is exactly equal to the
     * desired given type, except that it will accept an additional leading
     * argument of type {@code VarHandle}.
     *
     * @param accessMode the VarHandle access mode
     * @param type the desired target type
     * @return a method handle suitable for invoking an access mode method of
     *         any VarHandle whose access mode type is of the given type.
     * @since 9
     */
    static public
    MethodHandle varHandleExactInvoker(VarHandle.AccessMode accessMode, MethodType type) {
        return type.invokers().varHandleMethodExactInvoker(accessMode);
    }

    /**
     * Produces a special <em>invoker method handle</em> which can be used to
     * invoke a signature-polymorphic access mode method on any VarHandle whose
     * associated access mode type is compatible with the given type.
     * The resulting invoker will have a type which is exactly equal to the
     * desired given type, except that it will accept an additional leading
     * argument of type {@code VarHandle}.
     * <p>
     * Before invoking its target, if the access mode type differs from the
     * desired given type, the invoker will apply reference casts as necessary
     * and box, unbox, or widen primitive values, as if by
     * {@link MethodHandle#asType asType}.  Similarly, the return value will be
     * converted as necessary.
     * <p>
     * This method is equivalent to the following code (though it may be more
     * efficient): {@code publicLookup().findVirtual(VarHandle.class, accessMode.name(), type)}
     *
     * @param accessMode the VarHandle access mode
     * @param type the desired target type
     * @return a method handle suitable for invoking an access mode method of
     *         any VarHandle whose access mode type is convertible to the given
     *         type.
     * @since 9
     */
    static public
    MethodHandle varHandleInvoker(VarHandle.AccessMode accessMode, MethodType type) {
        return type.invokers().varHandleMethodInvoker(accessMode);
    }

    static /*non-public*/
    MethodHandle basicInvoker(MethodType type) {
        return type.invokers().basicInvoker();
    }

     /// method handle modification (creation from other method handles)

    /**
     * Produces a method handle which adapts the type of the
     * given method handle to a new type by pairwise argument and return type conversion.
     * The original type and new type must have the same number of arguments.
     * The resulting method handle is guaranteed to report a type
     * which is equal to the desired new type.
     * <p>
     * If the original type and new type are equal, returns target.
     * <p>
     * The same conversions are allowed as for {@link MethodHandle#asType MethodHandle.asType},
     * and some additional conversions are also applied if those conversions fail.
     * Given types <em>T0</em>, <em>T1</em>, one of the following conversions is applied
     * if possible, before or instead of any conversions done by {@code asType}:
     * <ul>
     * <li>If <em>T0</em> and <em>T1</em> are references, and <em>T1</em> is an interface type,
     *     then the value of type <em>T0</em> is passed as a <em>T1</em> without a cast.
     *     (This treatment of interfaces follows the usage of the bytecode verifier.)
     * <li>If <em>T0</em> is boolean and <em>T1</em> is another primitive,
     *     the boolean is converted to a byte value, 1 for true, 0 for false.
     *     (This treatment follows the usage of the bytecode verifier.)
     * <li>If <em>T1</em> is boolean and <em>T0</em> is another primitive,
     *     <em>T0</em> is converted to byte via Java casting conversion (JLS 5.5),
     *     and the low order bit of the result is tested, as if by {@code (x & 1) != 0}.
     * <li>If <em>T0</em> and <em>T1</em> are primitives other than boolean,
     *     then a Java casting conversion (JLS 5.5) is applied.
     *     (Specifically, <em>T0</em> will convert to <em>T1</em> by
     *     widening and/or narrowing.)
     * <li>If <em>T0</em> is a reference and <em>T1</em> a primitive, an unboxing
     *     conversion will be applied at runtime, possibly followed
     *     by a Java casting conversion (JLS 5.5) on the primitive value,
     *     possibly followed by a conversion from byte to boolean by testing
     *     the low-order bit.
     * <li>If <em>T0</em> is a reference and <em>T1</em> a primitive,
     *     and if the reference is null at runtime, a zero value is introduced.
     * </ul>
     * @param target the method handle to invoke after arguments are retyped
     * @param newType the expected type of the new method handle
     * @return a method handle which delegates to the target after performing
     *           any necessary argument conversions, and arranges for any
     *           necessary return value conversions
     * @throws NullPointerException if either argument is null
     * @throws WrongMethodTypeException if the conversion cannot be made
     * @see MethodHandle#asType
     */
    public static
    MethodHandle explicitCastArguments(MethodHandle target, MethodType newType) {
        explicitCastArgumentsChecks(target, newType);
        // use the asTypeCache when possible:
        MethodType oldType = target.type();
        if (oldType == newType)  return target;
        if (oldType.explicitCastEquivalentToAsType(newType)) {
            return target.asFixedArity().asType(newType);
        }
        return MethodHandleImpl.makePairwiseConvert(target, newType, false);
    }

    private static void explicitCastArgumentsChecks(MethodHandle target, MethodType newType) {
        if (target.type().parameterCount() != newType.parameterCount()) {
            throw new WrongMethodTypeException("cannot explicitly cast " + target + " to " + newType);
        }
    }

    /**
     * Produces a method handle which adapts the calling sequence of the
     * given method handle to a new type, by reordering the arguments.
     * The resulting method handle is guaranteed to report a type
     * which is equal to the desired new type.
     * <p>
     * The given array controls the reordering.
     * Call {@code #I} the number of incoming parameters (the value
     * {@code newType.parameterCount()}, and call {@code #O} the number
     * of outgoing parameters (the value {@code target.type().parameterCount()}).
     * Then the length of the reordering array must be {@code #O},
     * and each element must be a non-negative number less than {@code #I}.
     * For every {@code N} less than {@code #O}, the {@code N}-th
     * outgoing argument will be taken from the {@code I}-th incoming
     * argument, where {@code I} is {@code reorder[N]}.
     * <p>
     * No argument or return value conversions are applied.
     * The type of each incoming argument, as determined by {@code newType},
     * must be identical to the type of the corresponding outgoing parameter
     * or parameters in the target method handle.
     * The return type of {@code newType} must be identical to the return
     * type of the original target.
     * <p>
     * The reordering array need not specify an actual permutation.
     * An incoming argument will be duplicated if its index appears
     * more than once in the array, and an incoming argument will be dropped
     * if its index does not appear in the array.
     * As in the case of {@link #dropArguments(MethodHandle,int,List) dropArguments},
     * incoming arguments which are not mentioned in the reordering array
     * may be of any type, as determined only by {@code newType}.
     * <blockquote><pre>{@code
import static java.lang.invoke.MethodHandles.*;
import static java.lang.invoke.MethodType.*;
...
MethodType intfn1 = methodType(int.class, int.class);
MethodType intfn2 = methodType(int.class, int.class, int.class);
MethodHandle sub = ... (int x, int y) -> (x-y) ...;
assert(sub.type().equals(intfn2));
MethodHandle sub1 = permuteArguments(sub, intfn2, 0, 1);
MethodHandle rsub = permuteArguments(sub, intfn2, 1, 0);
assert((int)rsub.invokeExact(1, 100) == 99);
MethodHandle add = ... (int x, int y) -> (x+y) ...;
assert(add.type().equals(intfn2));
MethodHandle twice = permuteArguments(add, intfn1, 0, 0);
assert(twice.type().equals(intfn1));
assert((int)twice.invokeExact(21) == 42);
     * }</pre></blockquote>
     * <p>
     * <em>Note:</em> The resulting adapter is never a {@linkplain MethodHandle#asVarargsCollector
     * variable-arity method handle}, even if the original target method handle was.
     * @param target the method handle to invoke after arguments are reordered
     * @param newType the expected type of the new method handle
     * @param reorder an index array which controls the reordering
     * @return a method handle which delegates to the target after it
     *           drops unused arguments and moves and/or duplicates the other arguments
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException if the index array length is not equal to
     *                  the arity of the target, or if any index array element
     *                  not a valid index for a parameter of {@code newType},
     *                  or if two corresponding parameter types in
     *                  {@code target.type()} and {@code newType} are not identical,
     */
    public static
    MethodHandle permuteArguments(MethodHandle target, MethodType newType, int... reorder) {
        reorder = reorder.clone();  // get a private copy
        MethodType oldType = target.type();
        permuteArgumentChecks(reorder, newType, oldType);
        // first detect dropped arguments and handle them separately
        int[] originalReorder = reorder;
        BoundMethodHandle result = target.rebind();
        LambdaForm form = result.form;
        int newArity = newType.parameterCount();
        // Normalize the reordering into a real permutation,
        // by removing duplicates and adding dropped elements.
        // This somewhat improves lambda form caching, as well
        // as simplifying the transform by breaking it up into steps.
        for (int ddIdx; (ddIdx = findFirstDupOrDrop(reorder, newArity)) != 0; ) {
            if (ddIdx > 0) {
                // We found a duplicated entry at reorder[ddIdx].
                // Example:  (x,y,z)->asList(x,y,z)
                // permuted by [1*,0,1] => (a0,a1)=>asList(a1,a0,a1)
                // permuted by [0,1,0*] => (a0,a1)=>asList(a0,a1,a0)
                // The starred element corresponds to the argument
                // deleted by the dupArgumentForm transform.
                int srcPos = ddIdx, dstPos = srcPos, dupVal = reorder[srcPos];
                boolean killFirst = false;
                for (int val; (val = reorder[--dstPos]) != dupVal; ) {
                    // Set killFirst if the dup is larger than an intervening position.
                    // This will remove at least one inversion from the permutation.
                    if (dupVal > val) killFirst = true;
                }
                if (!killFirst) {
                    srcPos = dstPos;
                    dstPos = ddIdx;
                }
                form = form.editor().dupArgumentForm(1 + srcPos, 1 + dstPos);
                assert (reorder[srcPos] == reorder[dstPos]);
                oldType = oldType.dropParameterTypes(dstPos, dstPos + 1);
                // contract the reordering by removing the element at dstPos
                int tailPos = dstPos + 1;
                System.arraycopy(reorder, tailPos, reorder, dstPos, reorder.length - tailPos);
                reorder = Arrays.copyOf(reorder, reorder.length - 1);
            } else {
                int dropVal = ~ddIdx, insPos = 0;
                while (insPos < reorder.length && reorder[insPos] < dropVal) {
                    // Find first element of reorder larger than dropVal.
                    // This is where we will insert the dropVal.
                    insPos += 1;
                }
                Class<?> ptype = newType.parameterType(dropVal);
                form = form.editor().addArgumentForm(1 + insPos, BasicType.basicType(ptype));
                oldType = oldType.insertParameterTypes(insPos, ptype);
                // expand the reordering by inserting an element at insPos
                int tailPos = insPos + 1;
                reorder = Arrays.copyOf(reorder, reorder.length + 1);
                System.arraycopy(reorder, insPos, reorder, tailPos, reorder.length - tailPos);
                reorder[insPos] = dropVal;
            }
            assert (permuteArgumentChecks(reorder, newType, oldType));
        }
        assert (reorder.length == newArity);  // a perfect permutation
        // Note:  This may cache too many distinct LFs. Consider backing off to varargs code.
        form = form.editor().permuteArgumentsForm(1, reorder);
        if (newType == result.type() && form == result.internalForm())
            return result;
        return result.copyWith(newType, form);
    }

    /**
     * Return an indication of any duplicate or omission in reorder.
     * If the reorder contains a duplicate entry, return the index of the second occurrence.
     * Otherwise, return ~(n), for the first n in [0..newArity-1] that is not present in reorder.
     * Otherwise, return zero.
     * If an element not in [0..newArity-1] is encountered, return reorder.length.
     */
    private static int findFirstDupOrDrop(int[] reorder, int newArity) {
        final int BIT_LIMIT = 63;  // max number of bits in bit mask
        if (newArity < BIT_LIMIT) {
            long mask = 0;
            for (int i = 0; i < reorder.length; i++) {
                int arg = reorder[i];
                if (arg >= newArity) {
                    return reorder.length;
                }
                long bit = 1L << arg;
                if ((mask & bit) != 0) {
                    return i;  // >0 indicates a dup
                }
                mask |= bit;
            }
            if (mask == (1L << newArity) - 1) {
                assert(Long.numberOfTrailingZeros(Long.lowestOneBit(~mask)) == newArity);
                return 0;
            }
            // find first zero
            long zeroBit = Long.lowestOneBit(~mask);
            int zeroPos = Long.numberOfTrailingZeros(zeroBit);
            assert(zeroPos <= newArity);
            if (zeroPos == newArity) {
                return 0;
            }
            return ~zeroPos;
        } else {
            // same algorithm, different bit set
            BitSet mask = new BitSet(newArity);
            for (int i = 0; i < reorder.length; i++) {
                int arg = reorder[i];
                if (arg >= newArity) {
                    return reorder.length;
                }
                if (mask.get(arg)) {
                    return i;  // >0 indicates a dup
                }
                mask.set(arg);
            }
            int zeroPos = mask.nextClearBit(0);
            assert(zeroPos <= newArity);
            if (zeroPos == newArity) {
                return 0;
            }
            return ~zeroPos;
        }
    }

    private static boolean permuteArgumentChecks(int[] reorder, MethodType newType, MethodType oldType) {
        if (newType.returnType() != oldType.returnType())
            throw newIllegalArgumentException("return types do not match",
                    oldType, newType);
        if (reorder.length == oldType.parameterCount()) {
            int limit = newType.parameterCount();
            boolean bad = false;
            for (int j = 0; j < reorder.length; j++) {
                int i = reorder[j];
                if (i < 0 || i >= limit) {
                    bad = true; break;
                }
                Class<?> src = newType.parameterType(i);
                Class<?> dst = oldType.parameterType(j);
                if (src != dst)
                    throw newIllegalArgumentException("parameter types do not match after reorder",
                            oldType, newType);
            }
            if (!bad)  return true;
        }
        throw newIllegalArgumentException("bad reorder array: "+Arrays.toString(reorder));
    }

    /**
     * Produces a method handle of the requested return type which returns the given
     * constant value every time it is invoked.
     * <p>
     * Before the method handle is returned, the passed-in value is converted to the requested type.
     * If the requested type is primitive, widening primitive conversions are attempted,
     * else reference conversions are attempted.
     * <p>The returned method handle is equivalent to {@code identity(type).bindTo(value)}.
     * @param type the return type of the desired method handle
     * @param value the value to return
     * @return a method handle of the given return type and no arguments, which always returns the given value
     * @throws NullPointerException if the {@code type} argument is null
     * @throws ClassCastException if the value cannot be converted to the required return type
     * @throws IllegalArgumentException if the given type is {@code void.class}
     */
    public static
    MethodHandle constant(Class<?> type, Object value) {
        if (type.isPrimitive()) {
            if (type == void.class)
                throw newIllegalArgumentException("void type");
            Wrapper w = Wrapper.forPrimitiveType(type);
            value = w.convert(value, type);
            if (w.zero().equals(value))
                return zero(w, type);
            return insertArguments(identity(type), 0, value);
        } else {
            if (value == null)
                return zero(Wrapper.OBJECT, type);
            return identity(type).bindTo(value);
        }
    }

    /**
     * Produces a method handle which returns its sole argument when invoked.
     * @param type the type of the sole parameter and return value of the desired method handle
     * @return a unary method handle which accepts and returns the given type
     * @throws NullPointerException if the argument is null
     * @throws IllegalArgumentException if the given type is {@code void.class}
     */
    public static
    MethodHandle identity(Class<?> type) {
        Wrapper btw = (type.isPrimitive() ? Wrapper.forPrimitiveType(type) : Wrapper.OBJECT);
        int pos = btw.ordinal();
        MethodHandle ident = IDENTITY_MHS[pos];
        if (ident == null) {
            ident = setCachedMethodHandle(IDENTITY_MHS, pos, makeIdentity(btw.primitiveType()));
        }
        if (ident.type().returnType() == type)
            return ident;
        // something like identity(Foo.class); do not bother to intern these
        assert (btw == Wrapper.OBJECT);
        return makeIdentity(type);
    }

    /**
     * Produces a constant method handle of the requested return type which
     * returns the default value for that type every time it is invoked.
     * The resulting constant method handle will have no side effects.
     * <p>The returned method handle is equivalent to {@code empty(methodType(type))}.
     * It is also equivalent to {@code explicitCastArguments(constant(Object.class, null), methodType(type))},
     * since {@code explicitCastArguments} converts {@code null} to default values.
     * @param type the expected return type of the desired method handle
     * @return a constant method handle that takes no arguments
     *         and returns the default value of the given type (or void, if the type is void)
     * @throws NullPointerException if the argument is null
     * @see MethodHandles#constant
     * @see MethodHandles#empty
     * @see MethodHandles#explicitCastArguments
     * @since 9
     */
    public static  MethodHandle zero(Class<?> type) {
        Objects.requireNonNull(type);
        return type.isPrimitive() ?  zero(Wrapper.forPrimitiveType(type), type) : zero(Wrapper.OBJECT, type);
    }

    private static MethodHandle identityOrVoid(Class<?> type) {
        return type == void.class ? zero(type) : identity(type);
    }

    /**
     * Produces a method handle of the requested type which ignores any arguments, does nothing,
     * and returns a suitable default depending on the return type.
     * That is, it returns a zero primitive value, a {@code null}, or {@code void}.
     * <p>The returned method handle is equivalent to
     * {@code dropArguments(zero(type.returnType()), 0, type.parameterList())}.
     * <p>
     * @apiNote Given a predicate and target, a useful "if-then" construct can be produced as
     * {@code guardWithTest(pred, target, empty(target.type())}.
     * @param type the type of the desired method handle
     * @return a constant method handle of the given type, which returns a default value of the given return type
     * @throws NullPointerException if the argument is null
     * @see MethodHandles#zero
     * @see MethodHandles#constant
     * @since 9
     */
    public static  MethodHandle empty(MethodType type) {
        Objects.requireNonNull(type);
        return dropArguments(zero(type.returnType()), 0, type.parameterList());
    }

    private static final MethodHandle[] IDENTITY_MHS = new MethodHandle[Wrapper.COUNT];
    private static MethodHandle makeIdentity(Class<?> ptype) {
        MethodType mtype = methodType(ptype, ptype);
        LambdaForm lform = LambdaForm.identityForm(BasicType.basicType(ptype));
        return MethodHandleImpl.makeIntrinsic(mtype, lform, Intrinsic.IDENTITY);
    }

    private static MethodHandle zero(Wrapper btw, Class<?> rtype) {
        int pos = btw.ordinal();
        MethodHandle zero = ZERO_MHS[pos];
        if (zero == null) {
            zero = setCachedMethodHandle(ZERO_MHS, pos, makeZero(btw.primitiveType()));
        }
        if (zero.type().returnType() == rtype)
            return zero;
        assert(btw == Wrapper.OBJECT);
        return makeZero(rtype);
    }
    private static final MethodHandle[] ZERO_MHS = new MethodHandle[Wrapper.COUNT];
    private static MethodHandle makeZero(Class<?> rtype) {
        MethodType mtype = methodType(rtype);
        LambdaForm lform = LambdaForm.zeroForm(BasicType.basicType(rtype));
        return MethodHandleImpl.makeIntrinsic(mtype, lform, Intrinsic.ZERO);
    }

    private static synchronized MethodHandle setCachedMethodHandle(MethodHandle[] cache, int pos, MethodHandle value) {
        // Simulate a CAS, to avoid racy duplication of results.
        MethodHandle prev = cache[pos];
        if (prev != null) return prev;
        return cache[pos] = value;
    }

    /**
     * Provides a target method handle with one or more <em>bound arguments</em>
     * in advance of the method handle's invocation.
     * The formal parameters to the target corresponding to the bound
     * arguments are called <em>bound parameters</em>.
     * Returns a new method handle which saves away the bound arguments.
     * When it is invoked, it receives arguments for any non-bound parameters,
     * binds the saved arguments to their corresponding parameters,
     * and calls the original target.
     * <p>
     * The type of the new method handle will drop the types for the bound
     * parameters from the original target type, since the new method handle
     * will no longer require those arguments to be supplied by its callers.
     * <p>
     * Each given argument object must match the corresponding bound parameter type.
     * If a bound parameter type is a primitive, the argument object
     * must be a wrapper, and will be unboxed to produce the primitive value.
     * <p>
     * The {@code pos} argument selects which parameters are to be bound.
     * It may range between zero and <i>N-L</i> (inclusively),
     * where <i>N</i> is the arity of the target method handle
     * and <i>L</i> is the length of the values array.
     * <p>
     * <em>Note:</em> The resulting adapter is never a {@linkplain MethodHandle#asVarargsCollector
     * variable-arity method handle}, even if the original target method handle was.
     * @param target the method handle to invoke after the argument is inserted
     * @param pos where to insert the argument (zero for the first)
     * @param values the series of arguments to insert
     * @return a method handle which inserts an additional argument,
     *         before calling the original method handle
     * @throws NullPointerException if the target or the {@code values} array is null
     * @see MethodHandle#bindTo
     */
    public static
    MethodHandle insertArguments(MethodHandle target, int pos, Object... values) {
        int insCount = values.length;
        Class<?>[] ptypes = insertArgumentsChecks(target, insCount, pos);
        if (insCount == 0)  return target;
        BoundMethodHandle result = target.rebind();
        for (int i = 0; i < insCount; i++) {
            Object value = values[i];
            Class<?> ptype = ptypes[pos+i];
            if (ptype.isPrimitive()) {
                result = insertArgumentPrimitive(result, pos, ptype, value);
            } else {
                value = ptype.cast(value);  // throw CCE if needed
                result = result.bindArgumentL(pos, value);
            }
        }
        return result;
    }

    private static BoundMethodHandle insertArgumentPrimitive(BoundMethodHandle result, int pos,
                                                             Class<?> ptype, Object value) {
        Wrapper w = Wrapper.forPrimitiveType(ptype);
        // perform unboxing and/or primitive conversion
        value = w.convert(value, ptype);
        switch (w) {
        case INT:     return result.bindArgumentI(pos, (int)value);
        case LONG:    return result.bindArgumentJ(pos, (long)value);
        case FLOAT:   return result.bindArgumentF(pos, (float)value);
        case DOUBLE:  return result.bindArgumentD(pos, (double)value);
        default:      return result.bindArgumentI(pos, ValueConversions.widenSubword(value));
        }
    }

    private static Class<?>[] insertArgumentsChecks(MethodHandle target, int insCount, int pos) throws RuntimeException {
        MethodType oldType = target.type();
        int outargs = oldType.parameterCount();
        int inargs  = outargs - insCount;
        if (inargs < 0)
            throw newIllegalArgumentException("too many values to insert");
        if (pos < 0 || pos > inargs)
            throw newIllegalArgumentException("no argument type to append");
        return oldType.ptypes();
    }

    /**
     * Produces a method handle which will discard some dummy arguments
     * before calling some other specified <i>target</i> method handle.
     * The type of the new method handle will be the same as the target's type,
     * except it will also include the dummy argument types,
     * at some given position.
     * <p>
     * The {@code pos} argument may range between zero and <i>N</i>,
     * where <i>N</i> is the arity of the target.
     * If {@code pos} is zero, the dummy arguments will precede
     * the target's real arguments; if {@code pos} is <i>N</i>
     * they will come after.
     * <p>
     * <b>Example:</b>
     * <blockquote><pre>{@code
import static java.lang.invoke.MethodHandles.*;
import static java.lang.invoke.MethodType.*;
...
MethodHandle cat = lookup().findVirtual(String.class,
  "concat", methodType(String.class, String.class));
assertEquals("xy", (String) cat.invokeExact("x", "y"));
MethodType bigType = cat.type().insertParameterTypes(0, int.class, String.class);
MethodHandle d0 = dropArguments(cat, 0, bigType.parameterList().subList(0,2));
assertEquals(bigType, d0.type());
assertEquals("yz", (String) d0.invokeExact(123, "x", "y", "z"));
     * }</pre></blockquote>
     * <p>
     * This method is also equivalent to the following code:
     * <blockquote><pre>
     * {@link #dropArguments(MethodHandle,int,Class...) dropArguments}{@code (target, pos, valueTypes.toArray(new Class[0]))}
     * </pre></blockquote>
     * @param target the method handle to invoke after the arguments are dropped
     * @param valueTypes the type(s) of the argument(s) to drop
     * @param pos position of first argument to drop (zero for the leftmost)
     * @return a method handle which drops arguments of the given types,
     *         before calling the original method handle
     * @throws NullPointerException if the target is null,
     *                              or if the {@code valueTypes} list or any of its elements is null
     * @throws IllegalArgumentException if any element of {@code valueTypes} is {@code void.class},
     *                  or if {@code pos} is negative or greater than the arity of the target,
     *                  or if the new method handle's type would have too many parameters
     */
    public static
    MethodHandle dropArguments(MethodHandle target, int pos, List<Class<?>> valueTypes) {
        return dropArguments0(target, pos, copyTypes(valueTypes.toArray()));
    }

    private static List<Class<?>> copyTypes(Object[] array) {
        return Arrays.asList(Arrays.copyOf(array, array.length, Class[].class));
    }

    private static
    MethodHandle dropArguments0(MethodHandle target, int pos, List<Class<?>> valueTypes) {
        MethodType oldType = target.type();  // get NPE
        int dropped = dropArgumentChecks(oldType, pos, valueTypes);
        MethodType newType = oldType.insertParameterTypes(pos, valueTypes);
        if (dropped == 0)  return target;
        BoundMethodHandle result = target.rebind();
        LambdaForm lform = result.form;
        int insertFormArg = 1 + pos;
        for (Class<?> ptype : valueTypes) {
            lform = lform.editor().addArgumentForm(insertFormArg++, BasicType.basicType(ptype));
        }
        result = result.copyWith(newType, lform);
        return result;
    }

    private static int dropArgumentChecks(MethodType oldType, int pos, List<Class<?>> valueTypes) {
        int dropped = valueTypes.size();
        MethodType.checkSlotCount(dropped);
        int outargs = oldType.parameterCount();
        int inargs  = outargs + dropped;
        if (pos < 0 || pos > outargs)
            throw newIllegalArgumentException("no argument type to remove"
                    + Arrays.asList(oldType, pos, valueTypes, inargs, outargs)
                    );
        return dropped;
    }

    /**
     * Produces a method handle which will discard some dummy arguments
     * before calling some other specified <i>target</i> method handle.
     * The type of the new method handle will be the same as the target's type,
     * except it will also include the dummy argument types,
     * at some given position.
     * <p>
     * The {@code pos} argument may range between zero and <i>N</i>,
     * where <i>N</i> is the arity of the target.
     * If {@code pos} is zero, the dummy arguments will precede
     * the target's real arguments; if {@code pos} is <i>N</i>
     * they will come after.
     * @apiNote
     * <blockquote><pre>{@code
import static java.lang.invoke.MethodHandles.*;
import static java.lang.invoke.MethodType.*;
...
MethodHandle cat = lookup().findVirtual(String.class,
  "concat", methodType(String.class, String.class));
assertEquals("xy", (String) cat.invokeExact("x", "y"));
MethodHandle d0 = dropArguments(cat, 0, String.class);
assertEquals("yz", (String) d0.invokeExact("x", "y", "z"));
MethodHandle d1 = dropArguments(cat, 1, String.class);
assertEquals("xz", (String) d1.invokeExact("x", "y", "z"));
MethodHandle d2 = dropArguments(cat, 2, String.class);
assertEquals("xy", (String) d2.invokeExact("x", "y", "z"));
MethodHandle d12 = dropArguments(cat, 1, int.class, boolean.class);
assertEquals("xz", (String) d12.invokeExact("x", 12, true, "z"));
     * }</pre></blockquote>
     * <p>
     * This method is also equivalent to the following code:
     * <blockquote><pre>
     * {@link #dropArguments(MethodHandle,int,List) dropArguments}{@code (target, pos, Arrays.asList(valueTypes))}
     * </pre></blockquote>
     * @param target the method handle to invoke after the arguments are dropped
     * @param valueTypes the type(s) of the argument(s) to drop
     * @param pos position of first argument to drop (zero for the leftmost)
     * @return a method handle which drops arguments of the given types,
     *         before calling the original method handle
     * @throws NullPointerException if the target is null,
     *                              or if the {@code valueTypes} array or any of its elements is null
     * @throws IllegalArgumentException if any element of {@code valueTypes} is {@code void.class},
     *                  or if {@code pos} is negative or greater than the arity of the target,
     *                  or if the new method handle's type would have
     *                  <a href="MethodHandle.html#maxarity">too many parameters</a>
     */
    public static
    MethodHandle dropArguments(MethodHandle target, int pos, Class<?>... valueTypes) {
        return dropArguments0(target, pos, copyTypes(valueTypes));
    }

    // private version which allows caller some freedom with error handling
    private static MethodHandle dropArgumentsToMatch(MethodHandle target, int skip, List<Class<?>> newTypes, int pos,
                                      boolean nullOnFailure) {
        newTypes = copyTypes(newTypes.toArray());
        List<Class<?>> oldTypes = target.type().parameterList();
        int match = oldTypes.size();
        if (skip != 0) {
            if (skip < 0 || skip > match) {
                throw newIllegalArgumentException("illegal skip", skip, target);
            }
            oldTypes = oldTypes.subList(skip, match);
            match -= skip;
        }
        List<Class<?>> addTypes = newTypes;
        int add = addTypes.size();
        if (pos != 0) {
            if (pos < 0 || pos > add) {
                throw newIllegalArgumentException("illegal pos", pos, newTypes);
            }
            addTypes = addTypes.subList(pos, add);
            add -= pos; assert(addTypes.size() == add);
        }
        // Do not add types which already match the existing arguments.
        if (match > add || !oldTypes.equals(addTypes.subList(0, match))) {
            if (nullOnFailure) {
                return null;
            }
            throw newIllegalArgumentException("argument lists do not match", oldTypes, newTypes);
        }
        addTypes = addTypes.subList(match, add);
        add -= match; assert(addTypes.size() == add);
        // newTypes:     (   P*[pos], M*[match], A*[add] )
        // target: ( S*[skip],        M*[match]  )
        MethodHandle adapter = target;
        if (add > 0) {
            adapter = dropArguments0(adapter, skip+ match, addTypes);
        }
        // adapter: (S*[skip],        M*[match], A*[add] )
        if (pos > 0) {
            adapter = dropArguments0(adapter, skip, newTypes.subList(0, pos));
       }
        // adapter: (S*[skip], P*[pos], M*[match], A*[add] )
        return adapter;
    }

    /**
     * Adapts a target method handle to match the given parameter type list, if necessary, by adding dummy arguments.
     * Some leading parameters are first skipped; they will be left unchanged and are otherwise ignored.
     * The remaining types in the target's parameter type list must be contained as a sub-list of the given type list,
     * at the given position.
     * Any non-matching parameter types (before or after the matching sub-list) are inserted in corresponding
     * positions of the target method handle's parameters, as if by {@link #dropArguments}.
     * (More precisely, elements in the new list before {@code pos} are inserted into the target list at {@code skip},
     * while elements in the new list after the match beginning at {@code pos} are inserted at the end of the
     * target list.)
     * The target's return type will be unchanged.
     * @apiNote
     * Two method handles whose argument lists are "effectively identical" (i.e., identical
     * in a common prefix) may be mutually converted to a common type
     * by two calls to {@code dropArgumentsToMatch}, as follows:
     * <blockquote><pre>{@code
import static java.lang.invoke.MethodHandles.*;
import static java.lang.invoke.MethodType.*;
...
...
MethodHandle h0 = constant(boolean.class, true);
MethodHandle h1 = lookup().findVirtual(String.class, "concat", methodType(String.class, String.class));
MethodType bigType = h1.type().insertParameterTypes(1, String.class, int.class);
MethodHandle h2 = dropArguments(h1, 0, bigType.parameterList());
if (h1.type().parameterCount() < h2.type().parameterCount())
    h1 = dropArgumentsToMatch(h1, 0, h2.type().parameterList(), 0);  // lengthen h1
else
    h2 = dropArgumentsToMatch(h2, 0, h1.type().parameterList(), 0);    // lengthen h2
MethodHandle h3 = guardWithTest(h0, h1, h2);
assertEquals("xy", h3.invoke("x", "y", 1, "a", "b", "c"));
     * }</pre></blockquote>
     * @param target the method handle to adapt
     * @param skip number of targets parameters to disregard (they will be unchanged)
     * @param newTypes the desired argument list of the method handle
     * @param pos place in {@code newTypes} where the non-skipped target parameters must occur
     * @return a possibly adapted method handle
     * @throws NullPointerException if either argument is null
     * @throws IllegalArgumentException if any element of {@code newTypes} is {@code void.class},
     *         or if {@code skip} is negative or greater than the arity of the target,
     *         or if {@code pos} is negative or greater than the newTypes list size,
     *         or if the non-skipped target parameter types match the new types at {@code pos}
     * @since 9
     */
    public static
    MethodHandle dropArgumentsToMatch(MethodHandle target, int skip, List<Class<?>> newTypes, int pos) {
        Objects.requireNonNull(target);
        Objects.requireNonNull(newTypes);
        return dropArgumentsToMatch(target, skip, newTypes, pos, false);
    }

    /**
     * Adapts a target method handle by pre-processing
     * one or more of its arguments, each with its own unary filter function,
     * and then calling the target with each pre-processed argument
     * replaced by the result of its corresponding filter function.
     * <p>
     * The pre-processing is performed by one or more method handles,
     * specified in the elements of the {@code filters} array.
     * The first element of the filter array corresponds to the {@code pos}
     * argument of the target, and so on in sequence.
     * <p>
     * Null arguments in the array are treated as identity functions,
     * and the corresponding arguments left unchanged.
     * (If there are no non-null elements in the array, the original target is returned.)
     * Each filter is applied to the corresponding argument of the adapter.
     * <p>
     * If a filter {@code F} applies to the {@code N}th argument of
     * the target, then {@code F} must be a method handle which
     * takes exactly one argument.  The type of {@code F}'s sole argument
     * replaces the corresponding argument type of the target
     * in the resulting adapted method handle.
     * The return type of {@code F} must be identical to the corresponding
     * parameter type of the target.
     * <p>
     * It is an error if there are elements of {@code filters}
     * (null or not)
     * which do not correspond to argument positions in the target.
     * <p><b>Example:</b>
     * <blockquote><pre>{@code
import static java.lang.invoke.MethodHandles.*;
import static java.lang.invoke.MethodType.*;
...
MethodHandle cat = lookup().findVirtual(String.class,
  "concat", methodType(String.class, String.class));
MethodHandle upcase = lookup().findVirtual(String.class,
  "toUpperCase", methodType(String.class));
assertEquals("xy", (String) cat.invokeExact("x", "y"));
MethodHandle f0 = filterArguments(cat, 0, upcase);
assertEquals("Xy", (String) f0.invokeExact("x", "y")); // Xy
MethodHandle f1 = filterArguments(cat, 1, upcase);
assertEquals("xY", (String) f1.invokeExact("x", "y")); // xY
MethodHandle f2 = filterArguments(cat, 0, upcase, upcase);
assertEquals("XY", (String) f2.invokeExact("x", "y")); // XY
     * }</pre></blockquote>
     * <p>Here is pseudocode for the resulting adapter. In the code, {@code T}
     * denotes the return type of both the {@code target} and resulting adapter.
     * {@code P}/{@code p} and {@code B}/{@code b} represent the types and values
     * of the parameters and arguments that precede and follow the filter position
     * {@code pos}, respectively. {@code A[i]}/{@code a[i]} stand for the types and
     * values of the filtered parameters and arguments; they also represent the
     * return types of the {@code filter[i]} handles. The latter accept arguments
     * {@code v[i]} of type {@code V[i]}, which also appear in the signature of
     * the resulting adapter.
     * <blockquote><pre>{@code
     * T target(P... p, A[i]... a[i], B... b);
     * A[i] filter[i](V[i]);
     * T adapter(P... p, V[i]... v[i], B... b) {
     *   return target(p..., filter[i](v[i])..., b...);
     * }
     * }</pre></blockquote>
     * <p>
     * <em>Note:</em> The resulting adapter is never a {@linkplain MethodHandle#asVarargsCollector
     * variable-arity method handle}, even if the original target method handle was.
     *
     * @param target the method handle to invoke after arguments are filtered
     * @param pos the position of the first argument to filter
     * @param filters method handles to call initially on filtered arguments
     * @return method handle which incorporates the specified argument filtering logic
     * @throws NullPointerException if the target is null
     *                              or if the {@code filters} array is null
     * @throws IllegalArgumentException if a non-null element of {@code filters}
     *          does not match a corresponding argument type of target as described above,
     *          or if the {@code pos+filters.length} is greater than {@code target.type().parameterCount()},
     *          or if the resulting method handle's type would have
     *          <a href="MethodHandle.html#maxarity">too many parameters</a>
     */
    public static
    MethodHandle filterArguments(MethodHandle target, int pos, MethodHandle... filters) {
        filterArgumentsCheckArity(target, pos, filters);
        MethodHandle adapter = target;
        int curPos = pos-1;  // pre-incremented
        for (MethodHandle filter : filters) {
            curPos += 1;
            if (filter == null)  continue;  // ignore null elements of filters
            adapter = filterArgument(adapter, curPos, filter);
        }
        return adapter;
    }

    /*non-public*/ static
    MethodHandle filterArgument(MethodHandle target, int pos, MethodHandle filter) {
        filterArgumentChecks(target, pos, filter);
        MethodType targetType = target.type();
        MethodType filterType = filter.type();
        BoundMethodHandle result = target.rebind();
        Class<?> newParamType = filterType.parameterType(0);
        LambdaForm lform = result.editor().filterArgumentForm(1 + pos, BasicType.basicType(newParamType));
        MethodType newType = targetType.changeParameterType(pos, newParamType);
        result = result.copyWithExtendL(newType, lform, filter);
        return result;
    }

    private static void filterArgumentsCheckArity(MethodHandle target, int pos, MethodHandle[] filters) {
        MethodType targetType = target.type();
        int maxPos = targetType.parameterCount();
        if (pos + filters.length > maxPos)
            throw newIllegalArgumentException("too many filters");
    }

    private static void filterArgumentChecks(MethodHandle target, int pos, MethodHandle filter) throws RuntimeException {
        MethodType targetType = target.type();
        MethodType filterType = filter.type();
        if (filterType.parameterCount() != 1
            || filterType.returnType() != targetType.parameterType(pos))
            throw newIllegalArgumentException("target and filter types do not match", targetType, filterType);
    }

    /**
     * Adapts a target method handle by pre-processing
     * a sub-sequence of its arguments with a filter (another method handle).
     * The pre-processed arguments are replaced by the result (if any) of the
     * filter function.
     * The target is then called on the modified (usually shortened) argument list.
     * <p>
     * If the filter returns a value, the target must accept that value as
     * its argument in position {@code pos}, preceded and/or followed by
     * any arguments not passed to the filter.
     * If the filter returns void, the target must accept all arguments
     * not passed to the filter.
     * No arguments are reordered, and a result returned from the filter
     * replaces (in order) the whole subsequence of arguments originally
     * passed to the adapter.
     * <p>
     * The argument types (if any) of the filter
     * replace zero or one argument types of the target, at position {@code pos},
     * in the resulting adapted method handle.
     * The return type of the filter (if any) must be identical to the
     * argument type of the target at position {@code pos}, and that target argument
     * is supplied by the return value of the filter.
     * <p>
     * In all cases, {@code pos} must be greater than or equal to zero, and
     * {@code pos} must also be less than or equal to the target's arity.
     * <p><b>Example:</b>
     * <blockquote><pre>{@code
import static java.lang.invoke.MethodHandles.*;
import static java.lang.invoke.MethodType.*;
...
MethodHandle deepToString = publicLookup()
  .findStatic(Arrays.class, "deepToString", methodType(String.class, Object[].class));

MethodHandle ts1 = deepToString.asCollector(String[].class, 1);
assertEquals("[strange]", (String) ts1.invokeExact("strange"));

MethodHandle ts2 = deepToString.asCollector(String[].class, 2);
assertEquals("[up, down]", (String) ts2.invokeExact("up", "down"));

MethodHandle ts3 = deepToString.asCollector(String[].class, 3);
MethodHandle ts3_ts2 = collectArguments(ts3, 1, ts2);
assertEquals("[top, [up, down], strange]",
             (String) ts3_ts2.invokeExact("top", "up", "down", "strange"));

MethodHandle ts3_ts2_ts1 = collectArguments(ts3_ts2, 3, ts1);
assertEquals("[top, [up, down], [strange]]",
             (String) ts3_ts2_ts1.invokeExact("top", "up", "down", "strange"));

MethodHandle ts3_ts2_ts3 = collectArguments(ts3_ts2, 1, ts3);
assertEquals("[top, [[up, down, strange], charm], bottom]",
             (String) ts3_ts2_ts3.invokeExact("top", "up", "down", "strange", "charm", "bottom"));
     * }</pre></blockquote>
     * <p>Here is pseudocode for the resulting adapter. In the code, {@code T}
     * represents the return type of the {@code target} and resulting adapter.
     * {@code V}/{@code v} stand for the return type and value of the
     * {@code filter}, which are also found in the signature and arguments of
     * the {@code target}, respectively, unless {@code V} is {@code void}.
     * {@code A}/{@code a} and {@code C}/{@code c} represent the parameter types
     * and values preceding and following the collection position, {@code pos},
     * in the {@code target}'s signature. They also turn up in the resulting
     * adapter's signature and arguments, where they surround
     * {@code B}/{@code b}, which represent the parameter types and arguments
     * to the {@code filter} (if any).
     * <blockquote><pre>{@code
     * T target(A...,V,C...);
     * V filter(B...);
     * T adapter(A... a,B... b,C... c) {
     *   V v = filter(b...);
     *   return target(a...,v,c...);
     * }
     * // and if the filter has no arguments:
     * T target2(A...,V,C...);
     * V filter2();
     * T adapter2(A... a,C... c) {
     *   V v = filter2();
     *   return target2(a...,v,c...);
     * }
     * // and if the filter has a void return:
     * T target3(A...,C...);
     * void filter3(B...);
     * T adapter3(A... a,B... b,C... c) {
     *   filter3(b...);
     *   return target3(a...,c...);
     * }
     * }</pre></blockquote>
     * <p>
     * A collection adapter {@code collectArguments(mh, 0, coll)} is equivalent to
     * one which first "folds" the affected arguments, and then drops them, in separate
     * steps as follows:
     * <blockquote><pre>{@code
     * mh = MethodHandles.dropArguments(mh, 1, coll.type().parameterList()); //step 2
     * mh = MethodHandles.foldArguments(mh, coll); //step 1
     * }</pre></blockquote>
     * If the target method handle consumes no arguments besides than the result
     * (if any) of the filter {@code coll}, then {@code collectArguments(mh, 0, coll)}
     * is equivalent to {@code filterReturnValue(coll, mh)}.
     * If the filter method handle {@code coll} consumes one argument and produces
     * a non-void result, then {@code collectArguments(mh, N, coll)}
     * is equivalent to {@code filterArguments(mh, N, coll)}.
     * Other equivalences are possible but would require argument permutation.
     * <p>
     * <em>Note:</em> The resulting adapter is never a {@linkplain MethodHandle#asVarargsCollector
     * variable-arity method handle}, even if the original target method handle was.
     *
     * @param target the method handle to invoke after filtering the subsequence of arguments
     * @param pos the position of the first adapter argument to pass to the filter,
     *            and/or the target argument which receives the result of the filter
     * @param filter method handle to call on the subsequence of arguments
     * @return method handle which incorporates the specified argument subsequence filtering logic
     * @throws NullPointerException if either argument is null
     * @throws IllegalArgumentException if the return type of {@code filter}
     *          is non-void and is not the same as the {@code pos} argument of the target,
     *          or if {@code pos} is not between 0 and the target's arity, inclusive,
     *          or if the resulting method handle's type would have
     *          <a href="MethodHandle.html#maxarity">too many parameters</a>
     * @see MethodHandles#foldArguments
     * @see MethodHandles#filterArguments
     * @see MethodHandles#filterReturnValue
     */
    public static
    MethodHandle collectArguments(MethodHandle target, int pos, MethodHandle filter) {
        MethodType newType = collectArgumentsChecks(target, pos, filter);
        MethodType collectorType = filter.type();
        BoundMethodHandle result = target.rebind();
        LambdaForm lform;
        if (collectorType.returnType().isArray() && filter.intrinsicName() == Intrinsic.NEW_ARRAY) {
            lform = result.editor().collectArgumentArrayForm(1 + pos, filter);
            if (lform != null) {
                return result.copyWith(newType, lform);
            }
        }
        lform = result.editor().collectArgumentsForm(1 + pos, collectorType.basicType());
        return result.copyWithExtendL(newType, lform, filter);
    }

    private static MethodType collectArgumentsChecks(MethodHandle target, int pos, MethodHandle filter) throws RuntimeException {
        MethodType targetType = target.type();
        MethodType filterType = filter.type();
        Class<?> rtype = filterType.returnType();
        List<Class<?>> filterArgs = filterType.parameterList();
        if (rtype == void.class) {
            return targetType.insertParameterTypes(pos, filterArgs);
        }
        if (rtype != targetType.parameterType(pos)) {
            throw newIllegalArgumentException("target and filter types do not match", targetType, filterType);
        }
        return targetType.dropParameterTypes(pos, pos+1).insertParameterTypes(pos, filterArgs);
    }

    /**
     * Adapts a target method handle by post-processing
     * its return value (if any) with a filter (another method handle).
     * The result of the filter is returned from the adapter.
     * <p>
     * If the target returns a value, the filter must accept that value as
     * its only argument.
     * If the target returns void, the filter must accept no arguments.
     * <p>
     * The return type of the filter
     * replaces the return type of the target
     * in the resulting adapted method handle.
     * The argument type of the filter (if any) must be identical to the
     * return type of the target.
     * <p><b>Example:</b>
     * <blockquote><pre>{@code
import static java.lang.invoke.MethodHandles.*;
import static java.lang.invoke.MethodType.*;
...
MethodHandle cat = lookup().findVirtual(String.class,
  "concat", methodType(String.class, String.class));
MethodHandle length = lookup().findVirtual(String.class,
  "length", methodType(int.class));
System.out.println((String) cat.invokeExact("x", "y")); // xy
MethodHandle f0 = filterReturnValue(cat, length);
System.out.println((int) f0.invokeExact("x", "y")); // 2
     * }</pre></blockquote>
     * <p>Here is pseudocode for the resulting adapter. In the code,
     * {@code T}/{@code t} represent the result type and value of the
     * {@code target}; {@code V}, the result type of the {@code filter}; and
     * {@code A}/{@code a}, the types and values of the parameters and arguments
     * of the {@code target} as well as the resulting adapter.
     * <blockquote><pre>{@code
     * T target(A...);
     * V filter(T);
     * V adapter(A... a) {
     *   T t = target(a...);
     *   return filter(t);
     * }
     * // and if the target has a void return:
     * void target2(A...);
     * V filter2();
     * V adapter2(A... a) {
     *   target2(a...);
     *   return filter2();
     * }
     * // and if the filter has a void return:
     * T target3(A...);
     * void filter3(V);
     * void adapter3(A... a) {
     *   T t = target3(a...);
     *   filter3(t);
     * }
     * }</pre></blockquote>
     * <p>
     * <em>Note:</em> The resulting adapter is never a {@linkplain MethodHandle#asVarargsCollector
     * variable-arity method handle}, even if the original target method handle was.
     * @param target the method handle to invoke before filtering the return value
     * @param filter method handle to call on the return value
     * @return method handle which incorporates the specified return value filtering logic
     * @throws NullPointerException if either argument is null
     * @throws IllegalArgumentException if the argument list of {@code filter}
     *          does not match the return type of target as described above
     */
    public static
    MethodHandle filterReturnValue(MethodHandle target, MethodHandle filter) {
        MethodType targetType = target.type();
        MethodType filterType = filter.type();
        filterReturnValueChecks(targetType, filterType);
        BoundMethodHandle result = target.rebind();
        BasicType rtype = BasicType.basicType(filterType.returnType());
        LambdaForm lform = result.editor().filterReturnForm(rtype, false);
        MethodType newType = targetType.changeReturnType(filterType.returnType());
        result = result.copyWithExtendL(newType, lform, filter);
        return result;
    }

    private static void filterReturnValueChecks(MethodType targetType, MethodType filterType) throws RuntimeException {
        Class<?> rtype = targetType.returnType();
        int filterValues = filterType.parameterCount();
        if (filterValues == 0
                ? (rtype != void.class)
                : (rtype != filterType.parameterType(0) || filterValues != 1))
            throw newIllegalArgumentException("target and filter types do not match", targetType, filterType);
    }

    /**
     * Adapts a target method handle by pre-processing
     * some of its arguments, and then calling the target with
     * the result of the pre-processing, inserted into the original
     * sequence of arguments.
     * <p>
     * The pre-processing is performed by {@code combiner}, a second method handle.
     * Of the arguments passed to the adapter, the first {@code N} arguments
     * are copied to the combiner, which is then called.
     * (Here, {@code N} is defined as the parameter count of the combiner.)
     * After this, control passes to the target, with any result
     * from the combiner inserted before the original {@code N} incoming
     * arguments.
     * <p>
     * If the combiner returns a value, the first parameter type of the target
     * must be identical with the return type of the combiner, and the next
     * {@code N} parameter types of the target must exactly match the parameters
     * of the combiner.
     * <p>
     * If the combiner has a void return, no result will be inserted,
     * and the first {@code N} parameter types of the target
     * must exactly match the parameters of the combiner.
     * <p>
     * The resulting adapter is the same type as the target, except that the
     * first parameter type is dropped,
     * if it corresponds to the result of the combiner.
     * <p>
     * (Note that {@link #dropArguments(MethodHandle,int,List) dropArguments} can be used to remove any arguments
     * that either the combiner or the target does not wish to receive.
     * If some of the incoming arguments are destined only for the combiner,
     * consider using {@link MethodHandle#asCollector asCollector} instead, since those
     * arguments will not need to be live on the stack on entry to the
     * target.)
     * <p><b>Example:</b>
     * <blockquote><pre>{@code
import static java.lang.invoke.MethodHandles.*;
import static java.lang.invoke.MethodType.*;
...
MethodHandle trace = publicLookup().findVirtual(java.io.PrintStream.class,
  "println", methodType(void.class, String.class))
    .bindTo(System.out);
MethodHandle cat = lookup().findVirtual(String.class,
  "concat", methodType(String.class, String.class));
assertEquals("boojum", (String) cat.invokeExact("boo", "jum"));
MethodHandle catTrace = foldArguments(cat, trace);
// also prints "boo":
assertEquals("boojum", (String) catTrace.invokeExact("boo", "jum"));
     * }</pre></blockquote>
     * <p>Here is pseudocode for the resulting adapter. In the code, {@code T}
     * represents the result type of the {@code target} and resulting adapter.
     * {@code V}/{@code v} represent the type and value of the parameter and argument
     * of {@code target} that precedes the folding position; {@code V} also is
     * the result type of the {@code combiner}. {@code A}/{@code a} denote the
     * types and values of the {@code N} parameters and arguments at the folding
     * position. {@code B}/{@code b} represent the types and values of the
     * {@code target} parameters and arguments that follow the folded parameters
     * and arguments.
     * <blockquote><pre>{@code
     * // there are N arguments in A...
     * T target(V, A[N]..., B...);
     * V combiner(A...);
     * T adapter(A... a, B... b) {
     *   V v = combiner(a...);
     *   return target(v, a..., b...);
     * }
     * // and if the combiner has a void return:
     * T target2(A[N]..., B...);
     * void combiner2(A...);
     * T adapter2(A... a, B... b) {
     *   combiner2(a...);
     *   return target2(a..., b...);
     * }
     * }</pre></blockquote>
     * <p>
     * <em>Note:</em> The resulting adapter is never a {@linkplain MethodHandle#asVarargsCollector
     * variable-arity method handle}, even if the original target method handle was.
     * @param target the method handle to invoke after arguments are combined
     * @param combiner method handle to call initially on the incoming arguments
     * @return method handle which incorporates the specified argument folding logic
     * @throws NullPointerException if either argument is null
     * @throws IllegalArgumentException if {@code combiner}'s return type
     *          is non-void and not the same as the first argument type of
     *          the target, or if the initial {@code N} argument types
     *          of the target
     *          (skipping one matching the {@code combiner}'s return type)
     *          are not identical with the argument types of {@code combiner}
     */
    public static
    MethodHandle foldArguments(MethodHandle target, MethodHandle combiner) {
        return foldArguments(target, 0, combiner);
    }

    private static Class<?> foldArgumentChecks(int foldPos, MethodType targetType, MethodType combinerType) {
        int foldArgs   = combinerType.parameterCount();
        Class<?> rtype = combinerType.returnType();
        int foldVals = rtype == void.class ? 0 : 1;
        int afterInsertPos = foldPos + foldVals;
        boolean ok = (targetType.parameterCount() >= afterInsertPos + foldArgs);
        if (ok) {
            for (int i = 0; i < foldArgs; i++) {
                if (combinerType.parameterType(i) != targetType.parameterType(i + afterInsertPos)) {
                    ok = false;
                    break;
                }
            }
        }
        if (ok && foldVals != 0 && combinerType.returnType() != targetType.parameterType(foldPos))
            ok = false;
        if (!ok)
            throw misMatchedTypes("target and combiner types", targetType, combinerType);
        return rtype;
    }

    /**
     * Makes a method handle which adapts a target method handle,
     * by guarding it with a test, a boolean-valued method handle.
     * If the guard fails, a fallback handle is called instead.
     * All three method handles must have the same corresponding
     * argument and return types, except that the return type
     * of the test must be boolean, and the test is allowed
     * to have fewer arguments than the other two method handles.
     * <p>
     * Here is pseudocode for the resulting adapter. In the code, {@code T}
     * represents the uniform result type of the three involved handles;
     * {@code A}/{@code a}, the types and values of the {@code target}
     * parameters and arguments that are consumed by the {@code test}; and
     * {@code B}/{@code b}, those types and values of the {@code target}
     * parameters and arguments that are not consumed by the {@code test}.
     * <blockquote><pre>{@code
     * boolean test(A...);
     * T target(A...,B...);
     * T fallback(A...,B...);
     * T adapter(A... a,B... b) {
     *   if (test(a...))
     *     return target(a..., b...);
     *   else
     *     return fallback(a..., b...);
     * }
     * }</pre></blockquote>
     * Note that the test arguments ({@code a...} in the pseudocode) cannot
     * be modified by execution of the test, and so are passed unchanged
     * from the caller to the target or fallback as appropriate.
     * @param test method handle used for test, must return boolean
     * @param target method handle to call if test passes
     * @param fallback method handle to call if test fails
     * @return method handle which incorporates the specified if/then/else logic
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException if {@code test} does not return boolean,
     *          or if all three method types do not match (with the return
     *          type of {@code test} changed to match that of the target).
     */
    public static
    MethodHandle guardWithTest(MethodHandle test,
                               MethodHandle target,
                               MethodHandle fallback) {
        MethodType gtype = test.type();
        MethodType ttype = target.type();
        MethodType ftype = fallback.type();
        if (!ttype.equals(ftype))
            throw misMatchedTypes("target and fallback types", ttype, ftype);
        if (gtype.returnType() != boolean.class)
            throw newIllegalArgumentException("guard type is not a predicate "+gtype);
        List<Class<?>> targs = ttype.parameterList();
        test = dropArgumentsToMatch(test, 0, targs, 0, true);
        if (test == null) {
            throw misMatchedTypes("target and test types", ttype, gtype);
        }
        return MethodHandleImpl.makeGuardWithTest(test, target, fallback);
    }

    static <T> RuntimeException misMatchedTypes(String what, T t1, T t2) {
        return newIllegalArgumentException(what + " must match: " + t1 + " != " + t2);
    }

    /**
     * Makes a method handle which adapts a target method handle,
     * by running it inside an exception handler.
     * If the target returns normally, the adapter returns that value.
     * If an exception matching the specified type is thrown, the fallback
     * handle is called instead on the exception, plus the original arguments.
     * <p>
     * The target and handler must have the same corresponding
     * argument and return types, except that handler may omit trailing arguments
     * (similarly to the predicate in {@link #guardWithTest guardWithTest}).
     * Also, the handler must have an extra leading parameter of {@code exType} or a supertype.
     * <p>
     * Here is pseudocode for the resulting adapter. In the code, {@code T}
     * represents the return type of the {@code target} and {@code handler},
     * and correspondingly that of the resulting adapter; {@code A}/{@code a},
     * the types and values of arguments to the resulting handle consumed by
     * {@code handler}; and {@code B}/{@code b}, those of arguments to the
     * resulting handle discarded by {@code handler}.
     * <blockquote><pre>{@code
     * T target(A..., B...);
     * T handler(ExType, A...);
     * T adapter(A... a, B... b) {
     *   try {
     *     return target(a..., b...);
     *   } catch (ExType ex) {
     *     return handler(ex, a...);
     *   }
     * }
     * }</pre></blockquote>
     * Note that the saved arguments ({@code a...} in the pseudocode) cannot
     * be modified by execution of the target, and so are passed unchanged
     * from the caller to the handler, if the handler is invoked.
     * <p>
     * The target and handler must return the same type, even if the handler
     * always throws.  (This might happen, for instance, because the handler
     * is simulating a {@code finally} clause).
     * To create such a throwing handler, compose the handler creation logic
     * with {@link #throwException throwException},
     * in order to create a method handle of the correct return type.
     * @param target method handle to call
     * @param exType the type of exception which the handler will catch
     * @param handler method handle to call if a matching exception is thrown
     * @return method handle which incorporates the specified try/catch logic
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException if {@code handler} does not accept
     *          the given exception type, or if the method handle types do
     *          not match in their return types and their
     *          corresponding parameters
     * @see MethodHandles#tryFinally(MethodHandle, MethodHandle)
     */
    public static
    MethodHandle catchException(MethodHandle target,
                                Class<? extends Throwable> exType,
                                MethodHandle handler) {
        MethodType ttype = target.type();
        MethodType htype = handler.type();
        if (!Throwable.class.isAssignableFrom(exType))
            throw new ClassCastException(exType.getName());
        if (htype.parameterCount() < 1 ||
            !htype.parameterType(0).isAssignableFrom(exType))
            throw newIllegalArgumentException("handler does not accept exception type "+exType);
        if (htype.returnType() != ttype.returnType())
            throw misMatchedTypes("target and handler return types", ttype, htype);
        handler = dropArgumentsToMatch(handler, 1, ttype.parameterList(), 0, true);
        if (handler == null) {
            throw misMatchedTypes("target and handler types", ttype, htype);
        }
        return MethodHandleImpl.makeGuardWithCatch(target, exType, handler);
    }

    /**
     * Produces a method handle which will throw exceptions of the given {@code exType}.
     * The method handle will accept a single argument of {@code exType},
     * and immediately throw it as an exception.
     * The method type will nominally specify a return of {@code returnType}.
     * The return type may be anything convenient:  It doesn't matter to the
     * method handle's behavior, since it will never return normally.
     * @param returnType the return type of the desired method handle
     * @param exType the parameter type of the desired method handle
     * @return method handle which can throw the given exceptions
     * @throws NullPointerException if either argument is null
     */
    public static
    MethodHandle throwException(Class<?> returnType, Class<? extends Throwable> exType) {
        if (!Throwable.class.isAssignableFrom(exType))
            throw new ClassCastException(exType.getName());
        return MethodHandleImpl.throwException(methodType(returnType, exType));
    }

    /**
     * Constructs a method handle representing a loop with several loop variables that are updated and checked upon each
     * iteration. Upon termination of the loop due to one of the predicates, a corresponding finalizer is run and
     * delivers the loop's result, which is the return value of the resulting handle.
     * <p>
     * Intuitively, every loop is formed by one or more "clauses", each specifying a local iteration value and/or a loop
     * exit. Each iteration of the loop executes each clause in order. A clause can optionally update its iteration
     * variable; it can also optionally perform a test and conditional loop exit. In order to express this logic in
     * terms of method handles, each clause will determine four actions:<ul>
     * <li>Before the loop executes, the initialization of an iteration variable or loop invariant local.
     * <li>When a clause executes, an update step for the iteration variable.
     * <li>When a clause executes, a predicate execution to test for loop exit.
     * <li>If a clause causes a loop exit, a finalizer execution to compute the loop's return value.
     * </ul>
     * <p>
     * Some of these clause parts may be omitted according to certain rules, and useful default behavior is provided in
     * this case. See below for a detailed description.
     * <p>
     * Each clause function, with the exception of clause initializers, is able to observe the entire loop state,
     * because it will be passed <em>all</em> current iteration variable values, as well as all incoming loop
     * parameters. Most clause functions will not need all of this information, but they will be formally connected as
     * if by {@link #dropArguments}.
     * <p>
     * Given a set of clauses, there is a number of checks and adjustments performed to connect all the parts of the
     * loop. They are spelled out in detail in the steps below. In these steps, every occurrence of the word "must"
     * corresponds to a place where {@link IllegalArgumentException} may be thrown if the required constraint is not met
     * by the inputs to the loop combinator. The term "effectively identical", applied to parameter type lists, means
     * that they must be identical, or else one list must be a proper prefix of the other.
     * <p>
     * <em>Step 0: Determine clause structure.</em><ol type="a">
     * <li>The clause array (of type {@code MethodHandle[][]} must be non-{@code null} and contain at least one element.
     * <li>The clause array may not contain {@code null}s or sub-arrays longer than four elements.
     * <li>Clauses shorter than four elements are treated as if they were padded by {@code null} elements to length
     * four. Padding takes place by appending elements to the array.
     * <li>Clauses with all {@code null}s are disregarded.
     * <li>Each clause is treated as a four-tuple of functions, called "init", "step", "pred", and "fini".
     * </ol>
     * <p>
     * <em>Step 1A: Determine iteration variables.</em><ol type="a">
     * <li>Examine init and step function return types, pairwise, to determine each clause's iteration variable type.
     * <li>If both functions are omitted, use {@code void}; else if one is omitted, use the other's return type; else
     * use the common return type (they must be identical).
     * <li>Form the list of return types (in clause order), omitting all occurrences of {@code void}.
     * <li>This list of types is called the "common prefix".
     * </ol>
     * <p>
     * <em>Step 1B: Determine loop parameters.</em><ul>
     * <li><b>If at least one init function is given,</b><ol type="a">
     *   <li>Examine init function parameter lists.
     *   <li>Omitted init functions are deemed to have {@code null} parameter lists.
     *   <li>All init function parameter lists must be effectively identical.
     *   <li>The longest parameter list (which is necessarily unique) is called the "common suffix".
     * </ol>
     * <li><b>If no init function is given,</b><ol type="a">
     *   <li>Examine the suffixes of the step, pred, and fini parameter lists, after removing the "common prefix".
     *   <li>The longest of these suffixes is taken as the "common suffix".
     * </ol></ul>
     * <p>
     * <em>Step 1C: Determine loop return type.</em><ol type="a">
     * <li>Examine fini function return types, disregarding omitted fini functions.
     * <li>If there are no fini functions, use {@code void} as the loop return type.
     * <li>Otherwise, use the common return type of the fini functions; they must all be identical.
     * </ol>
     * <p>
     * <em>Step 1D: Check other types.</em><ol type="a">
     * <li>There must be at least one non-omitted pred function.
     * <li>Every non-omitted pred function must have a {@code boolean} return type.
     * </ol>
     * <p>
     * <em>Step 2: Determine parameter lists.</em><ol type="a">
     * <li>The parameter list for the resulting loop handle will be the "common suffix".
     * <li>The parameter list for init functions will be adjusted to the "common suffix". (Note that their parameter
     * lists are already effectively identical to the common suffix.)
     * <li>The parameter list for non-init (step, pred, and fini) functions will be adjusted to the common prefix
     * followed by the common suffix, called the "common parameter sequence".
     * <li>Every non-init, non-omitted function parameter list must be effectively identical to the common parameter
     * sequence.
     * </ol>
     * <p>
     * <em>Step 3: Fill in omitted functions.</em><ol type="a">
     * <li>If an init function is omitted, use a {@linkplain #constant constant function} of the appropriate
     * {@code null}/zero/{@code false}/{@code void} type. (For this purpose, a constant {@code void} is simply a
     * function which does nothing and returns {@code void}; it can be obtained from another constant function by
     * {@linkplain MethodHandle#asType type conversion}.)
     * <li>If a step function is omitted, use an {@linkplain #identity identity function} of the clause's iteration
     * variable type; insert dropped argument parameters before the identity function parameter for the non-{@code void}
     * iteration variables of preceding clauses. (This will turn the loop variable into a local loop invariant.)
     * <li>If a pred function is omitted, the corresponding fini function must also be omitted.
     * <li>If a pred function is omitted, use a constant {@code true} function. (This will keep the loop going, as far
     * as this clause is concerned.)
     * <li>If a fini function is omitted, use a constant {@code null}/zero/{@code false}/{@code void} function of the
     * loop return type.
     * </ol>
     * <p>
     * <em>Step 4: Fill in missing parameter types.</em><ol type="a">
     * <li>At this point, every init function parameter list is effectively identical to the common suffix, but some
     * lists may be shorter. For every init function with a short parameter list, pad out the end of the list by
     * {@linkplain #dropArguments dropping arguments}.
     * <li>At this point, every non-init function parameter list is effectively identical to the common parameter
     * sequence, but some lists may be shorter. For every non-init function with a short parameter list, pad out the end
     * of the list by {@linkplain #dropArguments dropping arguments}.
     * </ol>
     * <p>
     * <em>Final observations.</em><ol type="a">
     * <li>After these steps, all clauses have been adjusted by supplying omitted functions and arguments.
     * <li>All init functions have a common parameter type list, which the final loop handle will also have.
     * <li>All fini functions have a common return type, which the final loop handle will also have.
     * <li>All non-init functions have a common parameter type list, which is the common parameter sequence, of
     * (non-{@code void}) iteration variables followed by loop parameters.
     * <li>Each pair of init and step functions agrees in their return types.
     * <li>Each non-init function will be able to observe the current values of all iteration variables, by means of the
     * common prefix.
     * </ol>
     * <p>
     * <em>Loop execution.</em><ol type="a">
     * <li>When the loop is called, the loop input values are saved in locals, to be passed (as the common suffix) to
     * every clause function. These locals are loop invariant.
     * <li>Each init function is executed in clause order (passing the common suffix) and the non-{@code void} values
     * are saved (as the common prefix) into locals. These locals are loop varying (unless their steps are identity
     * functions, as noted above).
     * <li>All function executions (except init functions) will be passed the common parameter sequence, consisting of
     * the non-{@code void} iteration values (in clause order) and then the loop inputs (in argument order).
     * <li>The step and pred functions are then executed, in clause order (step before pred), until a pred function
     * returns {@code false}.
     * <li>The non-{@code void} result from a step function call is used to update the corresponding loop variable. The
     * updated value is immediately visible to all subsequent function calls.
     * <li>If a pred function returns {@code false}, the corresponding fini function is called, and the resulting value
     * is returned from the loop as a whole.
     * </ol>
     * <p>
     * Here is pseudocode for the resulting loop handle. In the code, {@code V}/{@code v} represent the types / values
     * of loop variables; {@code A}/{@code a}, those of arguments passed to the resulting loop; and {@code R}, the
     * result types of finalizers as well as of the resulting loop.
     * <blockquote><pre>{@code
     * V... init...(A...);
     * boolean pred...(V..., A...);
     * V... step...(V..., A...);
     * R fini...(V..., A...);
     * R loop(A... a) {
     *   V... v... = init...(a...);
     *   for (;;) {
     *     for ((v, p, s, f) in (v..., pred..., step..., fini...)) {
     *       v = s(v..., a...);
     *       if (!p(v..., a...)) {
     *         return f(v..., a...);
     *       }
     *     }
     *   }
     * }
     * }</pre></blockquote>
     * <p>
     * @apiNote Example:
     * <blockquote><pre>{@code
     * // iterative implementation of the factorial function as a loop handle
     * static int one(int k) { return 1; }
     * static int inc(int i, int acc, int k) { return i + 1; }
     * static int mult(int i, int acc, int k) { return i * acc; }
     * static boolean pred(int i, int acc, int k) { return i < k; }
     * static int fin(int i, int acc, int k) { return acc; }
     * // assume MH_one, MH_inc, MH_mult, MH_pred, and MH_fin are handles to the above methods
     * // null initializer for counter, should initialize to 0
     * MethodHandle[] counterClause = new MethodHandle[]{null, MH_inc};
     * MethodHandle[] accumulatorClause = new MethodHandle[]{MH_one, MH_mult, MH_pred, MH_fin};
     * MethodHandle loop = MethodHandles.loop(counterClause, accumulatorClause);
     * assertEquals(120, loop.invoke(5));
     * }</pre></blockquote>
     *
     * @param clauses an array of arrays (4-tuples) of {@link MethodHandle}s adhering to the rules described above.
     *
     * @return a method handle embodying the looping behavior as defined by the arguments.
     *
     * @throws IllegalArgumentException in case any of the constraints described above is violated.
     *
     * @see MethodHandles#whileLoop(MethodHandle, MethodHandle, MethodHandle)
     * @see MethodHandles#doWhileLoop(MethodHandle, MethodHandle, MethodHandle)
     * @see MethodHandles#countedLoop(MethodHandle, MethodHandle, MethodHandle)
     * @see MethodHandles#iteratedLoop(MethodHandle, MethodHandle, MethodHandle)
     * @since 9
     */
    public static MethodHandle loop(MethodHandle[]... clauses) {
        // Step 0: determine clause structure.
        checkLoop0(clauses);

        List<MethodHandle> init = new ArrayList<>();
        List<MethodHandle> step = new ArrayList<>();
        List<MethodHandle> pred = new ArrayList<>();
        List<MethodHandle> fini = new ArrayList<>();

        Stream.of(clauses).filter(c -> Stream.of(c).anyMatch(Objects::nonNull)).forEach(clause -> {
            init.add(clause[0]); // all clauses have at least length 1
            step.add(clause.length <= 1 ? null : clause[1]);
            pred.add(clause.length <= 2 ? null : clause[2]);
            fini.add(clause.length <= 3 ? null : clause[3]);
        });

        assert Stream.of(init, step, pred, fini).map(List::size).distinct().count() == 1;
        final int nclauses = init.size();

        // Step 1A: determine iteration variables.
        final List<Class<?>> iterationVariableTypes = new ArrayList<>();
        for (int i = 0; i < nclauses; ++i) {
            MethodHandle in = init.get(i);
            MethodHandle st = step.get(i);
            if (in == null && st == null) {
                iterationVariableTypes.add(void.class);
            } else if (in != null && st != null) {
                checkLoop1a(i, in, st);
                iterationVariableTypes.add(in.type().returnType());
            } else {
                iterationVariableTypes.add(in == null ? st.type().returnType() : in.type().returnType());
            }
        }
        final List<Class<?>> commonPrefix = iterationVariableTypes.stream().filter(t -> t != void.class).
                collect(Collectors.toList());

        // Step 1B: determine loop parameters.
        final List<Class<?>> commonSuffix = buildCommonSuffix(init, step, pred, fini, commonPrefix.size());
        checkLoop1b(init, commonSuffix);

        // Step 1C: determine loop return type.
        // Step 1D: check other types.
        final Class<?> loopReturnType = fini.stream().filter(Objects::nonNull).map(MethodHandle::type).
                map(MethodType::returnType).findFirst().orElse(void.class);
        checkLoop1cd(pred, fini, loopReturnType);

        // Step 2: determine parameter lists.
        final List<Class<?>> commonParameterSequence = new ArrayList<>(commonPrefix);
        commonParameterSequence.addAll(commonSuffix);
        checkLoop2(step, pred, fini, commonParameterSequence);

        // Step 3: fill in omitted functions.
        for (int i = 0; i < nclauses; ++i) {
            Class<?> t = iterationVariableTypes.get(i);
            if (init.get(i) == null) {
                init.set(i, empty(methodType(t, commonSuffix)));
            }
            if (step.get(i) == null) {
                step.set(i, dropArgumentsToMatch(identityOrVoid(t), 0, commonParameterSequence, i));
            }
            if (pred.get(i) == null) {
                pred.set(i, dropArguments0(constant(boolean.class, true), 0, commonParameterSequence));
            }
            if (fini.get(i) == null) {
                fini.set(i, empty(methodType(t, commonParameterSequence)));
            }
        }

        // Step 4: fill in missing parameter types.
        List<MethodHandle> finit = fillParameterTypes(init, commonSuffix);
        List<MethodHandle> fstep = fillParameterTypes(step, commonParameterSequence);
        List<MethodHandle> fpred = fillParameterTypes(pred, commonParameterSequence);
        List<MethodHandle> ffini = fillParameterTypes(fini, commonParameterSequence);

        assert finit.stream().map(MethodHandle::type).map(MethodType::parameterList).
                allMatch(pl -> pl.equals(commonSuffix));
        assert Stream.of(fstep, fpred, ffini).flatMap(List::stream).map(MethodHandle::type).map(MethodType::parameterList).
                allMatch(pl -> pl.equals(commonParameterSequence));

        return MethodHandleImpl.makeLoop(loopReturnType, commonSuffix, finit, fstep, fpred, ffini);
    }

    private static List<MethodHandle> fillParameterTypes(List<MethodHandle> hs, final List<Class<?>> targetParams) {
        return hs.stream().map(h -> {
            int pc = h.type().parameterCount();
            int tpsize = targetParams.size();
            return pc < tpsize ? dropArguments0(h, pc, targetParams.subList(pc, tpsize)) : h;
        }).collect(Collectors.toList());
    }

    /**
     * Constructs a {@code while} loop from an initializer, a body, and a predicate. This is a convenience wrapper for
     * the {@linkplain #loop(MethodHandle[][]) generic loop combinator}.
     * <p>
     * The loop handle's result type is the same as the sole loop variable's, i.e., the result type of {@code init}.
     * The parameter type list of {@code init} also determines that of the resulting handle. The {@code pred} handle
     * must have an additional leading parameter of the same type as {@code init}'s result, and so must the {@code
     * body}. These constraints follow directly from those described for the {@linkplain MethodHandles#loop(MethodHandle[][])
     * generic loop combinator}.
     * <p>
     * Here is pseudocode for the resulting loop handle. In the code, {@code V}/{@code v} represent the type / value of
     * the sole loop variable as well as the result type of the loop; and {@code A}/{@code a}, that of the argument
     * passed to the loop.
     * <blockquote><pre>{@code
     * V init(A);
     * boolean pred(V, A);
     * V body(V, A);
     * V whileLoop(A a) {
     *   V v = init(a);
     *   while (pred(v, a)) {
     *     v = body(v, a);
     *   }
     *   return v;
     * }
     * }</pre></blockquote>
     * <p>
     * @apiNote Example:
     * <blockquote><pre>{@code
     * // implement the zip function for lists as a loop handle
     * static List<String> initZip(Iterator<String> a, Iterator<String> b) { return new ArrayList<>(); }
     * static boolean zipPred(List<String> zip, Iterator<String> a, Iterator<String> b) { return a.hasNext() && b.hasNext(); }
     * static List<String> zipStep(List<String> zip, Iterator<String> a, Iterator<String> b) {
     *   zip.add(a.next());
     *   zip.add(b.next());
     *   return zip;
     * }
     * // assume MH_initZip, MH_zipPred, and MH_zipStep are handles to the above methods
     * MethodHandle loop = MethodHandles.whileLoop(MH_initZip, MH_zipPred, MH_zipStep);
     * List<String> a = Arrays.asList("a", "b", "c", "d");
     * List<String> b = Arrays.asList("e", "f", "g", "h");
     * List<String> zipped = Arrays.asList("a", "e", "b", "f", "c", "g", "d", "h");
     * assertEquals(zipped, (List<String>) loop.invoke(a.iterator(), b.iterator()));
     * }</pre></blockquote>
     *
     * <p>
     * @implSpec The implementation of this method is equivalent to:
     * <blockquote><pre>{@code
     * MethodHandle whileLoop(MethodHandle init, MethodHandle pred, MethodHandle body) {
     *     MethodHandle[]
     *         checkExit = {null, null, pred, identity(init.type().returnType())},
     *         varBody = {init, body};
     *     return loop(checkExit, varBody);
     * }
     * }</pre></blockquote>
     *
     * @param init initializer: it should provide the initial value of the loop variable. This controls the loop's
     *             result type. Passing {@code null} or a {@code void} init function will make the loop's result type
     *             {@code void}.
     * @param pred condition for the loop, which may not be {@code null}.
     * @param body body of the loop, which may not be {@code null}.
     *
     * @return the value of the loop variable as the loop terminates.
     * @throws IllegalArgumentException if any argument has a type inconsistent with the loop structure
     *
     * @see MethodHandles#loop(MethodHandle[][])
     * @since 9
     */
    public static MethodHandle whileLoop(MethodHandle init, MethodHandle pred, MethodHandle body) {
        MethodHandle fin = init == null || init.type().returnType() == void.class ? zero(void.class) :
                identity(init.type().returnType());
        MethodHandle[] checkExit = {null, null, pred, fin};
        MethodHandle[] varBody = {init, body};
        return loop(checkExit, varBody);
    }

    /**
     * Constructs a {@code do-while} loop from an initializer, a body, and a predicate. This is a convenience wrapper
     * for the {@linkplain MethodHandles#loop(MethodHandle[][]) generic loop combinator}.
     * <p>
     * The loop handle's result type is the same as the sole loop variable's, i.e., the result type of {@code init}.
     * The parameter type list of {@code init} also determines that of the resulting handle. The {@code pred} handle
     * must have an additional leading parameter of the same type as {@code init}'s result, and so must the {@code
     * body}. These constraints follow directly from those described for the {@linkplain MethodHandles#loop(MethodHandle[][])
     * generic loop combinator}.
     * <p>
     * Here is pseudocode for the resulting loop handle. In the code, {@code V}/{@code v} represent the type / value of
     * the sole loop variable as well as the result type of the loop; and {@code A}/{@code a}, that of the argument
     * passed to the loop.
     * <blockquote><pre>{@code
     * V init(A);
     * boolean pred(V, A);
     * V body(V, A);
     * V doWhileLoop(A a) {
     *   V v = init(a);
     *   do {
     *     v = body(v, a);
     *   } while (pred(v, a));
     *   return v;
     * }
     * }</pre></blockquote>
     * <p>
     * @apiNote Example:
     * <blockquote><pre>{@code
     * // int i = 0; while (i < limit) { ++i; } return i; => limit
     * static int zero(int limit) { return 0; }
     * static int step(int i, int limit) { return i + 1; }
     * static boolean pred(int i, int limit) { return i < limit; }
     * // assume MH_zero, MH_step, and MH_pred are handles to the above methods
     * MethodHandle loop = MethodHandles.doWhileLoop(MH_zero, MH_step, MH_pred);
     * assertEquals(23, loop.invoke(23));
     * }</pre></blockquote>
     *
     * <p>
     * @implSpec The implementation of this method is equivalent to:
     * <blockquote><pre>{@code
     * MethodHandle doWhileLoop(MethodHandle init, MethodHandle body, MethodHandle pred) {
     *     MethodHandle[] clause = { init, body, pred, identity(init.type().returnType()) };
     *     return loop(clause);
     * }
     * }</pre></blockquote>
     *
     *
     * @param init initializer: it should provide the initial value of the loop variable. This controls the loop's
     *             result type. Passing {@code null} or a {@code void} init function will make the loop's result type
     *             {@code void}.
     * @param pred condition for the loop, which may not be {@code null}.
     * @param body body of the loop, which may not be {@code null}.
     *
     * @return the value of the loop variable as the loop terminates.
     * @throws IllegalArgumentException if any argument has a type inconsistent with the loop structure
     *
     * @see MethodHandles#loop(MethodHandle[][])
     * @since 9
     */
    public static MethodHandle doWhileLoop(MethodHandle init, MethodHandle body, MethodHandle pred) {
        MethodHandle fin = init == null || init.type().returnType() == void.class ? zero(void.class) :
                identity(init.type().returnType());
        MethodHandle[] clause = {init, body, pred, fin};
        return loop(clause);
    }

    /**
     * Constructs a loop that runs a given number of iterations. The loop counter is an {@code int} initialized from the
     * {@code iterations} handle evaluation result. The counter is passed to the {@code body} function, so that must
     * accept an initial {@code int} argument. The result of the loop execution is the final value of the additional
     * local state. This is a convenience wrapper for the {@linkplain MethodHandles#loop(MethodHandle[][]) generic loop
     * combinator}.
     * <p>
     * The result type and parameter type list of {@code init} determine those of the resulting handle. The {@code
     * iterations} handle must accept the same parameter types as {@code init} but return an {@code int}. The {@code
     * body} handle must accept the same parameter types as well, preceded by an {@code int} parameter for the counter,
     * and a parameter of the same type as {@code init}'s result. These constraints follow directly from those described
     * for the {@linkplain MethodHandles#loop(MethodHandle[][]) generic loop combinator}.
     * <p>
     * Here is pseudocode for the resulting loop handle. In the code, {@code V}/{@code v} represent the type / value of
     * the sole loop variable as well as the result type of the loop; and {@code A}/{@code a}, that of the argument
     * passed to the loop.
     * <blockquote><pre>{@code
     * int iterations(A);
     * V init(A);
     * V body(int, V, A);
     * V countedLoop(A a) {
     *   int end = iterations(a);
     *   V v = init(a);
     *   for (int i = 0; i < end; ++i) {
     *     v = body(i, v, a);
     *   }
     *   return v;
     * }
     * }</pre></blockquote>
     * <p>
     * @apiNote Example:
     * <blockquote><pre>{@code
     * // String s = "Lambdaman!"; for (int i = 0; i < 13; ++i) { s = "na " + s; } return s;
     * // => a variation on a well known theme
     * static String start(String arg) { return arg; }
     * static String step(int counter, String v, String arg) { return "na " + v; }
     * // assume MH_start and MH_step are handles to the two methods above
     * MethodHandle fit13 = MethodHandles.constant(int.class, 13);
     * MethodHandle loop = MethodHandles.countedLoop(fit13, MH_start, MH_step);
     * assertEquals("na na na na na na na na na na na na na Lambdaman!", loop.invoke("Lambdaman!"));
     * }</pre></blockquote>
     *
     * <p>
     * @implSpec The implementation of this method is equivalent to:
     * <blockquote><pre>{@code
     * MethodHandle countedLoop(MethodHandle iterations, MethodHandle init, MethodHandle body) {
     *     return countedLoop(null, iterations, init, body);  // null => constant zero
     * }
     * }</pre></blockquote>
     *
     * @param iterations a handle to return the number of iterations this loop should run.
     * @param init initializer for additional loop state. This determines the loop's result type.
     *             Passing {@code null} or a {@code void} init function will make the loop's result type
     *             {@code void}.
     * @param body the body of the loop, which must not be {@code null}.
     *             It must accept an initial {@code int} parameter (for the counter), and then any
     *             additional loop-local variable plus loop parameters.
     *
     * @return a method handle representing the loop.
     * @throws IllegalArgumentException if any argument has a type inconsistent with the loop structure
     *
     * @since 9
     */
    public static MethodHandle countedLoop(MethodHandle iterations, MethodHandle init, MethodHandle body) {
        return countedLoop(null, iterations, init, body);
    }

    /**
     * Constructs a loop that counts over a range of numbers. The loop counter is an {@code int} that will be
     * initialized to the {@code int} value returned from the evaluation of the {@code start} handle and run to the
     * value returned from {@code end} (exclusively) with a step width of 1. The counter value is passed to the {@code
     * body} function in each iteration; it has to accept an initial {@code int} parameter
     * for that. The result of the loop execution is the final value of the additional local state
     * obtained by running {@code init}.
     * This is a
     * convenience wrapper for the {@linkplain MethodHandles#loop(MethodHandle[][]) generic loop combinator}.
     * <p>
     * The constraints for the {@code init} and {@code body} handles are the same as for {@link
     * #countedLoop(MethodHandle, MethodHandle, MethodHandle)}. Additionally, the {@code start} and {@code end} handles
     * must return an {@code int} and accept the same parameters as {@code init}.
     * <p>
     * Here is pseudocode for the resulting loop handle. In the code, {@code V}/{@code v} represent the type / value of
     * the sole loop variable as well as the result type of the loop; and {@code A}/{@code a}, that of the argument
     * passed to the loop.
     * <blockquote><pre>{@code
     * int start(A);
     * int end(A);
     * V init(A);
     * V body(int, V, A);
     * V countedLoop(A a) {
     *   int s = start(a);
     *   int e = end(a);
     *   V v = init(a);
     *   for (int i = s; i < e; ++i) {
     *     v = body(i, v, a);
     *   }
     *   return v;
     * }
     * }</pre></blockquote>
     *
     * <p>
     * @implSpec The implementation of this method is equivalent to:
     * <blockquote><pre>{@code
     * MethodHandle countedLoop(MethodHandle start, MethodHandle end, MethodHandle init, MethodHandle body) {
     *     MethodHandle returnVar = dropArguments(identity(init.type().returnType()), 0, int.class, int.class);
     *     // assume MH_increment and MH_lessThan are handles to x+1 and x<y of type int,
     *     // assume MH_decrement is a handle to x-1 of type int
     *     MethodHandle[]
     *         indexVar = {start, MH_increment}, // i = start; i = i+1
     *         loopLimit = {end, null,
     *                       filterArgument(MH_lessThan, 0, MH_decrement), returnVar}, // i-1<end
     *         bodyClause = {init,
     *                       filterArgument(dropArguments(body, 1, int.class), 0, MH_decrement}; // v = body(i-1, v)
     *     return loop(indexVar, loopLimit, bodyClause);
     * }
     * }</pre></blockquote>
     *
     * @param start a handle to return the start value of the loop counter.
     *              If it is {@code null}, a constant zero is assumed.
     * @param end a non-{@code null} handle to return the end value of the loop counter (the loop will run to {@code end-1}).
     * @param init initializer for additional loop state. This determines the loop's result type.
     *             Passing {@code null} or a {@code void} init function will make the loop's result type
     *             {@code void}.
     * @param body the body of the loop, which must not be {@code null}.
     *             It must accept an initial {@code int} parameter (for the counter), and then any
     *             additional loop-local variable plus loop parameters.
     *
     * @return a method handle representing the loop.
     * @throws IllegalArgumentException if any argument has a type inconsistent with the loop structure
     *
     * @since 9
     */
    public static MethodHandle countedLoop(MethodHandle start, MethodHandle end, MethodHandle init, MethodHandle body) {
        Class<?> resultType;
        MethodHandle actualInit;
        if (init == null) {
            resultType = body == null ? void.class : body.type().returnType();
            actualInit = empty(methodType(resultType));
        } else {
            resultType = init.type().returnType();
            actualInit = init;
        }
        MethodHandle defaultResultHandle = resultType == void.class ? zero(void.class) : identity(resultType);
        MethodHandle actualBody = body == null ? dropArguments(defaultResultHandle, 0, int.class) : body;
        MethodHandle returnVar = dropArguments(defaultResultHandle, 0, int.class, int.class);
        MethodHandle actualEnd = end == null ? constant(int.class, 0) : end;
        MethodHandle decr = MethodHandleImpl.getConstantHandle(MethodHandleImpl.MH_decrementCounter);
        MethodHandle[] indexVar = {start, MethodHandleImpl.getConstantHandle(MethodHandleImpl.MH_countedLoopStep)};
        MethodHandle[] loopLimit = {actualEnd, null,
                filterArgument(MethodHandleImpl.getConstantHandle(MethodHandleImpl.MH_countedLoopPred), 0, decr),
                returnVar};
        MethodHandle[] bodyClause = {actualInit, filterArgument(dropArguments(actualBody, 1, int.class), 0, decr)};
        return loop(indexVar, loopLimit, bodyClause);
    }

    /**
     * Constructs a loop that ranges over the elements produced by an {@code Iterator<T>}.
     * The iterator will be produced by the evaluation of the {@code iterator} handle.
     * This handle must have {@link java.util.Iterator} as its return type.
     * If this handle is passed as {@code null} the method {@link Iterable#iterator} will be used instead,
     * and will be applied to a leading argument of the loop handle.
     * Each value produced by the iterator is passed to the {@code body}, which must accept an initial {@code T} parameter.
     * The result of the loop execution is the final value of the additional local state
     * obtained by running {@code init}.
     * <p>
     * This is a convenience wrapper for the
     * {@linkplain MethodHandles#loop(MethodHandle[][]) generic loop combinator}, and the constraints imposed on the {@code body}
     * handle follow directly from those described for the latter.
     * <p>
     * Here is pseudocode for the resulting loop handle. In the code, {@code V}/{@code v} represent the type / value of
     * the loop variable as well as the result type of the loop; {@code T}/{@code t}, that of the elements of the
     * structure the loop iterates over, and {@code A}/{@code a}, that of the argument passed to the loop.
     * <blockquote><pre>{@code
     * Iterator<T> iterator(A);  // defaults to Iterable::iterator
     * V init(A);
     * V body(T,V,A);
     * V iteratedLoop(A a) {
     *   Iterator<T> it = iterator(a);
     *   V v = init(a);
     *   for (T t : it) {
     *     v = body(t, v, a);
     *   }
     *   return v;
     * }
     * }</pre></blockquote>
     * <p>
     * The type {@code T} may be either a primitive or reference.
     * Since type {@code Iterator<T>} is erased in the method handle representation to the raw type
     * {@code Iterator}, the {@code iteratedLoop} combinator adjusts the leading argument type for {@code body}
     * to {@code Object} as if by the {@link MethodHandle#asType asType} conversion method.
     * Therefore, if an iterator of the wrong type appears as the loop is executed,
     * runtime exceptions may occur as the result of dynamic conversions performed by {@code asType}.
     * <p>
     * @apiNote Example:
     * <blockquote><pre>{@code
     * // reverse a list
     * static List<String> reverseStep(String e, List<String> r, List<String> l) {
     *   r.add(0, e);
     *   return r;
     * }
     * static List<String> newArrayList(List<String> l) { return new ArrayList<>(); }
     * // assume MH_reverseStep, MH_newArrayList are handles to the above methods
     * MethodHandle loop = MethodHandles.iteratedLoop(null, MH_newArrayList, MH_reverseStep);
     * List<String> list = Arrays.asList("a", "b", "c", "d", "e");
     * List<String> reversedList = Arrays.asList("e", "d", "c", "b", "a");
     * assertEquals(reversedList, (List<String>) loop.invoke(list));
     * }</pre></blockquote>
     * <p>
     * @implSpec The implementation of this method is equivalent to (excluding error handling):
     * <blockquote><pre>{@code
     * MethodHandle iteratedLoop(MethodHandle iterator, MethodHandle init, MethodHandle body) {
     *     // assume MH_next and MH_hasNext are handles to methods of Iterator
     *     Class<?> itype = iterator.type().returnType();
     *     Class<?> ttype = body.type().parameterType(0);
     *     MethodHandle returnVar = dropArguments(identity(init.type().returnType()), 0, itype);
     *     MethodHandle nextVal = MH_next.asType(MH_next.type().changeReturnType(ttype));
     *     MethodHandle[]
     *         iterVar = {iterator, null, MH_hasNext, returnVar}, // it = iterator(); while (it.hasNext)
     *         bodyClause = {init, filterArgument(body, 0, nextVal)};  // v = body(t, v, a);
     *     return loop(iterVar, bodyClause);
     * }
     * }</pre></blockquote>
     *
     * @param iterator a handle to return the iterator to start the loop.
     *             The handle must have {@link java.util.Iterator} as its return type.
     *             Passing {@code null} will make the loop call {@link Iterable#iterator()} on the first
     *             incoming value.
     * @param init initializer for additional loop state. This determines the loop's result type.
     *             Passing {@code null} or a {@code void} init function will make the loop's result type
     *             {@code void}.
     * @param body the body of the loop, which must not be {@code null}.
     *             It must accept an initial {@code T} parameter (for the iterated values), and then any
     *             additional loop-local variable plus loop parameters.
     *
     * @return a method handle embodying the iteration loop functionality.
     * @throws IllegalArgumentException if any argument has a type inconsistent with the loop structure
     *
     * @since 9
     */
    public static MethodHandle iteratedLoop(MethodHandle iterator, MethodHandle init, MethodHandle body) {
        checkIteratedLoop(iterator, body);
        Class<?> resultType = init == null ?
                body == null ? void.class : body.type().returnType() :
                init.type().returnType();
        boolean voidResult = resultType == void.class;

        MethodHandle initIterator;
        if (iterator == null) {
            MethodHandle initit = MethodHandleImpl.getConstantHandle(MethodHandleImpl.MH_initIterator);
            initIterator = initit.asType(initit.type().changeParameterType(0,
                    body.type().parameterType(voidResult ? 1 : 2)));
        } else {
            initIterator = iterator.asType(iterator.type().changeReturnType(Iterator.class));
        }

        Class<?> ttype = body.type().parameterType(0);

        MethodHandle returnVar =
                dropArguments(voidResult ? zero(void.class) : identity(resultType), 0, Iterator.class);
        MethodHandle initnx = MethodHandleImpl.getConstantHandle(MethodHandleImpl.MH_iterateNext);
        MethodHandle nextVal = initnx.asType(initnx.type().changeReturnType(ttype));

        MethodHandle[] iterVar = {initIterator, null, MethodHandleImpl.getConstantHandle(MethodHandleImpl.MH_iteratePred),
                returnVar};
        MethodHandle[] bodyClause = {init, filterArgument(body, 0, nextVal)};

        return loop(iterVar, bodyClause);
    }

    /**
     * Makes a method handle that adapts a {@code target} method handle by wrapping it in a {@code try-finally} block.
     * Another method handle, {@code cleanup}, represents the functionality of the {@code finally} block. Any exception
     * thrown during the execution of the {@code target} handle will be passed to the {@code cleanup} handle. The
     * exception will be rethrown, unless {@code cleanup} handle throws an exception first.  The
     * value returned from the {@code cleanup} handle's execution will be the result of the execution of the
     * {@code try-finally} handle.
     * <p>
     * The {@code cleanup} handle will be passed one or two additional leading arguments.
     * The first is the exception thrown during the
     * execution of the {@code target} handle, or {@code null} if no exception was thrown.
     * The second is the result of the execution of the {@code target} handle, or, if it throws an exception,
     * a {@code null}, zero, or {@code false} value of the required type is supplied as a placeholder.
     * The second argument is not present if the {@code target} handle has a {@code void} return type.
     * (Note that, except for argument type conversions, combinators represent {@code void} values in parameter lists
     * by omitting the corresponding paradoxical arguments, not by inserting {@code null} or zero values.)
     * <p>
     * The {@code target} and {@code cleanup} handles must have the same corresponding argument and return types, except
     * that the {@code cleanup} handle may omit trailing arguments. Also, the {@code cleanup} handle must have one or
     * two extra leading parameters:<ul>
     * <li>a {@code Throwable}, which will carry the exception thrown by the {@code target} handle (if any); and
     * <li>a parameter of the same type as the return type of both {@code target} and {@code cleanup}, which will carry
     * the result from the execution of the {@code target} handle.
     * This parameter is not present if the {@code target} returns {@code void}.
     * </ul>
     * <p>
     * The pseudocode for the resulting adapter looks as follows. In the code, {@code V} represents the result type of
     * the {@code try/finally} construct; {@code A}/{@code a}, the types and values of arguments to the resulting
     * handle consumed by the cleanup; and {@code B}/{@code b}, those of arguments to the resulting handle discarded by
     * the cleanup.
     * <blockquote><pre>{@code
     * V target(A..., B...);
     * V cleanup(Throwable, V, A...);
     * V adapter(A... a, B... b) {
     *   V result = (zero value for V);
     *   Throwable throwable = null;
     *   try {
     *     result = target(a..., b...);
     *   } catch (Throwable t) {
     *     throwable = t;
     *     throw t;
     *   } finally {
     *     result = cleanup(throwable, result, a...);
     *   }
     *   return result;
     * }
     * }</pre></blockquote>
     * <p>
     * Note that the saved arguments ({@code a...} in the pseudocode) cannot
     * be modified by execution of the target, and so are passed unchanged
     * from the caller to the cleanup, if it is invoked.
     * <p>
     * The target and cleanup must return the same type, even if the cleanup
     * always throws.
     * To create such a throwing cleanup, compose the cleanup logic
     * with {@link #throwException throwException},
     * in order to create a method handle of the correct return type.
     * <p>
     * Note that {@code tryFinally} never converts exceptions into normal returns.
     * In rare cases where exceptions must be converted in that way, first wrap
     * the target with {@link #catchException(MethodHandle, Class, MethodHandle)}
     * to capture an outgoing exception, and then wrap with {@code tryFinally}.
     *
     * @param target the handle whose execution is to be wrapped in a {@code try} block.
     * @param cleanup the handle that is invoked in the finally block.
     *
     * @return a method handle embodying the {@code try-finally} block composed of the two arguments.
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException if {@code cleanup} does not accept
     *          the required leading arguments, or if the method handle types do
     *          not match in their return types and their
     *          corresponding trailing parameters
     *
     * @see MethodHandles#catchException(MethodHandle, Class, MethodHandle)
     * @since 9
     */
    public static MethodHandle tryFinally(MethodHandle target, MethodHandle cleanup) {
        List<Class<?>> targetParamTypes = target.type().parameterList();
        List<Class<?>> cleanupParamTypes = cleanup.type().parameterList();
        Class<?> rtype = target.type().returnType();

        checkTryFinally(target, cleanup);

        // Match parameter lists: if the cleanup has a shorter parameter list than the target, add ignored arguments.
        // The cleanup parameter list (minus the leading Throwable and result parameters) must be a sublist of the
        // target parameter list.
        cleanup = dropArgumentsToMatch(cleanup, (rtype == void.class ? 1 : 2), targetParamTypes, 0);

        return MethodHandleImpl.makeTryFinally(target, cleanup, rtype, targetParamTypes);
    }

    /**
     * Adapts a target method handle by pre-processing some of its arguments, starting at a given position, and then
     * calling the target with the result of the pre-processing, inserted into the original sequence of arguments just
     * before the folded arguments.
     * <p>
     * This method is closely related to {@link #foldArguments(MethodHandle, MethodHandle)}, but allows to control the
     * position in the parameter list at which folding takes place. The argument controlling this, {@code pos}, is a
     * zero-based index. The aforementioned method {@link #foldArguments(MethodHandle, MethodHandle)} assumes position
     * 0.
     * <p>
     * @apiNote Example:
     * <blockquote><pre>{@code
    import static java.lang.invoke.MethodHandles.*;
    import static java.lang.invoke.MethodType.*;
    ...
    MethodHandle trace = publicLookup().findVirtual(java.io.PrintStream.class,
    "println", methodType(void.class, String.class))
    .bindTo(System.out);
    MethodHandle cat = lookup().findVirtual(String.class,
    "concat", methodType(String.class, String.class));
    assertEquals("boojum", (String) cat.invokeExact("boo", "jum"));
    MethodHandle catTrace = foldArguments(cat, 1, trace);
    // also prints "jum":
    assertEquals("boojum", (String) catTrace.invokeExact("boo", "jum"));
     * }</pre></blockquote>
     * <p>Here is pseudocode for the resulting adapter. In the code, {@code T}
     * represents the result type of the {@code target} and resulting adapter.
     * {@code V}/{@code v} represent the type and value of the parameter and argument
     * of {@code target} that precedes the folding position; {@code V} also is
     * the result type of the {@code combiner}. {@code A}/{@code a} denote the
     * types and values of the {@code N} parameters and arguments at the folding
     * position. {@code Z}/{@code z} and {@code B}/{@code b} represent the types
     * and values of the {@code target} parameters and arguments that precede and
     * follow the folded parameters and arguments starting at {@code pos},
     * respectively.
     * <blockquote><pre>{@code
     * // there are N arguments in A...
     * T target(Z..., V, A[N]..., B...);
     * V combiner(A...);
     * T adapter(Z... z, A... a, B... b) {
     *   V v = combiner(a...);
     *   return target(z..., v, a..., b...);
     * }
     * // and if the combiner has a void return:
     * T target2(Z..., A[N]..., B...);
     * void combiner2(A...);
     * T adapter2(Z... z, A... a, B... b) {
     *   combiner2(a...);
     *   return target2(z..., a..., b...);
     * }
     * }</pre></blockquote>
     * <p>
     * <em>Note:</em> The resulting adapter is never a {@linkplain MethodHandle#asVarargsCollector
     * variable-arity method handle}, even if the original target method handle was.
     *
     * @param target the method handle to invoke after arguments are combined
     * @param pos the position at which to start folding and at which to insert the folding result; if this is {@code
     *            0}, the effect is the same as for {@link #foldArguments(MethodHandle, MethodHandle)}.
     * @param combiner method handle to call initially on the incoming arguments
     * @return method handle which incorporates the specified argument folding logic
     * @throws NullPointerException if either argument is null
     * @throws IllegalArgumentException if {@code combiner}'s return type
     *          is non-void and not the same as the argument type at position {@code pos} of
     *          the target signature, or if the {@code N} argument types at position {@code pos}
     *          of the target signature
     *          (skipping one matching the {@code combiner}'s return type)
     *          are not identical with the argument types of {@code combiner}
     *
     * @see #foldArguments(MethodHandle, MethodHandle)
     * @since 9
     */
    public static MethodHandle foldArguments(MethodHandle target, int pos, MethodHandle combiner) {
        MethodType targetType = target.type();
        MethodType combinerType = combiner.type();
        Class<?> rtype = foldArgumentChecks(pos, targetType, combinerType);
        BoundMethodHandle result = target.rebind();
        boolean dropResult = rtype == void.class;
        LambdaForm lform = result.editor().foldArgumentsForm(1 + pos, dropResult, combinerType.basicType());
        MethodType newType = targetType;
        if (!dropResult) {
            newType = newType.dropParameterTypes(pos, pos + 1);
        }
        result = result.copyWithExtendL(newType, lform, combiner);
        return result;
    }


    private static void checkLoop0(MethodHandle[][] clauses) {
        if (clauses == null || clauses.length == 0) {
            throw newIllegalArgumentException("null or no clauses passed");
        }
        if (Stream.of(clauses).anyMatch(Objects::isNull)) {
            throw newIllegalArgumentException("null clauses are not allowed");
        }
        if (Stream.of(clauses).anyMatch(c -> c.length > 4)) {
            throw newIllegalArgumentException("All loop clauses must be represented as MethodHandle arrays with at most 4 elements.");
        }
    }

    private static void checkLoop1a(int i, MethodHandle in, MethodHandle st) {
        if (in.type().returnType() != st.type().returnType()) {
            throw misMatchedTypes("clause " + i + ": init and step return types", in.type().returnType(),
                    st.type().returnType());
        }
    }

    private static List<Class<?>> buildCommonSuffix(List<MethodHandle> init, List<MethodHandle> step, List<MethodHandle> pred, List<MethodHandle> fini, int cpSize) {
        final List<Class<?>> empty = List.of();
        final List<MethodHandle> nonNullInits = init.stream().filter(Objects::nonNull).collect(Collectors.toList());
        if (nonNullInits.isEmpty()) {
            final List<Class<?>> longest = Stream.of(step, pred, fini).flatMap(List::stream).filter(Objects::nonNull).
                    // take only those that can contribute to a common suffix because they are longer than the prefix
                    map(MethodHandle::type).filter(t -> t.parameterCount() > cpSize).map(MethodType::parameterList).
                    reduce((p, q) -> p.size() >= q.size() ? p : q).orElse(empty);
            return longest.size() == 0 ? empty : longest.subList(cpSize, longest.size());
        } else {
            return nonNullInits.stream().map(MethodHandle::type).map(MethodType::parameterList).
                    reduce((p, q) -> p.size() >= q.size() ? p : q).get();
        }
    }

    private static void checkLoop1b(List<MethodHandle> init, List<Class<?>> commonSuffix) {
        if (init.stream().filter(Objects::nonNull).map(MethodHandle::type).map(MethodType::parameterList).
                anyMatch(pl -> !pl.equals(commonSuffix.subList(0, pl.size())))) {
            throw newIllegalArgumentException("found non-effectively identical init parameter type lists: " + init +
                    " (common suffix: " + commonSuffix + ")");
        }
    }

    private static void checkLoop1cd(List<MethodHandle> pred, List<MethodHandle> fini, Class<?> loopReturnType) {
        if (fini.stream().filter(Objects::nonNull).map(MethodHandle::type).map(MethodType::returnType).
                anyMatch(t -> t != loopReturnType)) {
            throw newIllegalArgumentException("found non-identical finalizer return types: " + fini + " (return type: " +
                    loopReturnType + ")");
        }

        if (!pred.stream().filter(Objects::nonNull).findFirst().isPresent()) {
            throw newIllegalArgumentException("no predicate found", pred);
        }
        if (pred.stream().filter(Objects::nonNull).map(MethodHandle::type).map(MethodType::returnType).
                anyMatch(t -> t != boolean.class)) {
            throw newIllegalArgumentException("predicates must have boolean return type", pred);
        }
    }

    private static void checkLoop2(List<MethodHandle> step, List<MethodHandle> pred, List<MethodHandle> fini, List<Class<?>> commonParameterSequence) {
        final int cpSize = commonParameterSequence.size();
        if (Stream.of(step, pred, fini).flatMap(List::stream).filter(Objects::nonNull).map(MethodHandle::type).
                map(MethodType::parameterList).
                anyMatch(pl -> pl.size() > cpSize || !pl.equals(commonParameterSequence.subList(0, pl.size())))) {
            throw newIllegalArgumentException("found non-effectively identical parameter type lists:\nstep: " + step +
                    "\npred: " + pred + "\nfini: " + fini + " (common parameter sequence: " + commonParameterSequence + ")");
        }
    }

    private static void checkIteratedLoop(MethodHandle iterator, MethodHandle body) {
        if (null != iterator && !Iterator.class.isAssignableFrom(iterator.type().returnType())) {
            throw newIllegalArgumentException("iteratedLoop first argument must have Iterator return type");
        }
        if (null == body) {
            throw newIllegalArgumentException("iterated loop body must not be null");
        }
    }

    private static void checkTryFinally(MethodHandle target, MethodHandle cleanup) {
        Class<?> rtype = target.type().returnType();
        if (rtype != cleanup.type().returnType()) {
            throw misMatchedTypes("target and return types", cleanup.type().returnType(), rtype);
        }
        List<Class<?>> cleanupParamTypes = cleanup.type().parameterList();
        if (!Throwable.class.isAssignableFrom(cleanupParamTypes.get(0))) {
            throw misMatchedTypes("cleanup first argument and Throwable", cleanup.type(), Throwable.class);
        }
        if (rtype != void.class && cleanupParamTypes.get(1) != rtype) {
            throw misMatchedTypes("cleanup second argument and target return type", cleanup.type(), rtype);
        }
        // The cleanup parameter list (minus the leading Throwable and result parameters) must be a sublist of the
        // target parameter list.
        int cleanupArgIndex = rtype == void.class ? 1 : 2;
        List<Class<?>> cleanupArgSuffix = cleanupParamTypes.subList(cleanupArgIndex, cleanupParamTypes.size());
        List<Class<?>> targetParamTypes = target.type().parameterList();
        if (targetParamTypes.size() < cleanupArgSuffix.size() ||
                !cleanupArgSuffix.equals(targetParamTypes.subList(0, cleanupParamTypes.size() - cleanupArgIndex))) {
            throw misMatchedTypes("cleanup parameters after (Throwable,result) and target parameter list prefix",
                    cleanup.type(), target.type());
        }
    }

}
