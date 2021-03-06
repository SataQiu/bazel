// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.rules.cpp;

import static com.google.devtools.build.lib.packages.BuildType.LABEL;
import static com.google.devtools.build.lib.packages.BuildType.NODEP_LABEL;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Streams;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifact;
import com.google.devtools.build.lib.actions.MiddlemanFactory;
import com.google.devtools.build.lib.actions.ParamFileInfo;
import com.google.devtools.build.lib.actions.ParameterFile;
import com.google.devtools.build.lib.analysis.AnalysisUtils;
import com.google.devtools.build.lib.analysis.Expander;
import com.google.devtools.build.lib.analysis.PlatformConfiguration;
import com.google.devtools.build.lib.analysis.RuleConfiguredTargetBuilder;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.Runfiles;
import com.google.devtools.build.lib.analysis.StaticallyLinkedMarkerProvider;
import com.google.devtools.build.lib.analysis.TransitiveInfoCollection;
import com.google.devtools.build.lib.analysis.actions.ActionConstructionContext;
import com.google.devtools.build.lib.analysis.actions.CustomCommandLine;
import com.google.devtools.build.lib.analysis.actions.SpawnAction;
import com.google.devtools.build.lib.analysis.actions.SymlinkAction;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration;
import com.google.devtools.build.lib.analysis.config.CompilationMode;
import com.google.devtools.build.lib.analysis.configuredtargets.RuleConfiguredTarget.Mode;
import com.google.devtools.build.lib.analysis.platform.ToolchainInfo;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.packages.RuleClass.ConfiguredTargetFactory.RuleErrorException;
import com.google.devtools.build.lib.packages.RuleErrorConsumer;
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.FeatureConfiguration;
import com.google.devtools.build.lib.rules.cpp.LibraryToLink.CcLinkingContext;
import com.google.devtools.build.lib.rules.cpp.LibraryToLink.CcLinkingContext.Linkstamp;
import com.google.devtools.build.lib.rules.cpp.Link.LinkTargetType;
import com.google.devtools.build.lib.rules.proto.ProtoInfo;
import com.google.devtools.build.lib.shell.ShellUtils;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.StarlarkSemantics;
import com.google.devtools.build.lib.syntax.Type;
import com.google.devtools.build.lib.util.FileTypeSet;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Helper class for functionality shared by cpp related rules.
 *
 * <p>This class can be used only after the loading phase.
 */
public class CppHelper {

  static final PathFragment OBJS = PathFragment.create("_objs");
  static final PathFragment PIC_OBJS = PathFragment.create("_pic_objs");

  // TODO(bazel-team): should this use Link.SHARED_LIBRARY_FILETYPES?
  public static final FileTypeSet SHARED_LIBRARY_FILETYPES =
      FileTypeSet.of(CppFileTypes.SHARED_LIBRARY, CppFileTypes.VERSIONED_SHARED_LIBRARY);

  /** Base label of the c++ toolchain category. */
  public static final String TOOLCHAIN_TYPE_LABEL = "//tools/cpp:toolchain_type";

  private CppHelper() {
    // prevents construction
  }

  /** Returns the malloc implementation for the given target. */
  public static TransitiveInfoCollection mallocForTarget(
      RuleContext ruleContext, String mallocAttrName) {
    if (ruleContext.getFragment(CppConfiguration.class).customMalloc() != null) {
      return ruleContext.getPrerequisite(":default_malloc", Mode.TARGET);
    } else {
      return ruleContext.getPrerequisite(mallocAttrName, Mode.TARGET);
    }
  }

  public static TransitiveInfoCollection mallocForTarget(RuleContext ruleContext) {
    return mallocForTarget(ruleContext, "malloc");
  }

  /**
   * Expands Make variables in a list of string and tokenizes the result. If the package feature
   * no_copts_tokenization is set, tokenize only items consisting of a single make variable.
   *
   * @param ruleContext the ruleContext to be used as the context of Make variable expansion
   * @param attributeName the name of the attribute to use in error reporting
   * @param input the list of strings to expand
   * @return a list of strings containing the expanded and tokenized values for the attribute
   */
  private static List<String> expandMakeVariables(
      RuleContext ruleContext, String attributeName, List<String> input) {
    boolean tokenization = !ruleContext.getFeatures().contains("no_copts_tokenization");

    List<String> tokens = new ArrayList<>();
    Expander expander = ruleContext.getExpander().withDataExecLocations();
    for (String token : input) {
      // Legacy behavior: tokenize all items.
      if (tokenization) {
        expander.tokenizeAndExpandMakeVars(tokens, attributeName, token);
      } else {
        String exp = expander.expandSingleMakeVariable(attributeName, token);
        if (exp != null) {
          try {
            ShellUtils.tokenize(tokens, exp);
          } catch (ShellUtils.TokenizationException e) {
            ruleContext.attributeError(attributeName, e.getMessage());
          }
        } else {
          tokens.add(expander.expand(attributeName, token));
        }
      }
    }
    return ImmutableList.copyOf(tokens);
  }

  /** Returns the tokenized values of the copts attribute to copts. */
  // Called from CcCommon and CcSupport (Google's internal version of proto_library).
  public static ImmutableList<String> getAttributeCopts(RuleContext ruleContext) {
    String attr = "copts";
    Preconditions.checkArgument(ruleContext.getRule().isAttrDefined(attr, Type.STRING_LIST));
    List<String> unexpanded = ruleContext.attributes().get(attr, Type.STRING_LIST);
    return ImmutableList.copyOf(expandMakeVariables(ruleContext, attr, unexpanded));
  }

  // Called from CcCommon.
  static ImmutableList<String> getPackageCopts(RuleContext ruleContext) {
    List<String> unexpanded = ruleContext.getRule().getPackage().getDefaultCopts();
    return ImmutableList.copyOf(expandMakeVariables(ruleContext, "copts", unexpanded));
  }

  /** Tokenizes and expands make variables. */
  public static List<String> expandLinkopts(
      RuleContext ruleContext, String attrName, Iterable<String> values) {
    List<String> result = new ArrayList<>();
    Expander expander = ruleContext.getExpander().withDataExecLocations();
    for (String value : values) {
      expander.tokenizeAndExpandMakeVars(result, attrName, value);
    }
    return result;
  }

  /**
   * Returns the immutable list of linker options for fully statically linked outputs. Does not
   * include command-line options passed via --linkopt or --linkopts.
   *
   * @param config the CppConfiguration for this build
   * @param toolchain the c++ toolchain
   * @param sharedLib true if the output is a shared lib, false if it's an executable
   */
  public static ImmutableList<String> getFullyStaticLinkOptions(
      CppConfiguration config, CcToolchainProvider toolchain, boolean sharedLib) {
    if (sharedLib) {
      return toolchain.getSharedLibraryLinkOptions(
          toolchain.getLegacyMostlyStaticLinkFlags(config.getCompilationMode()));
    } else {
      return toolchain.getLegacyFullyStaticLinkFlags(config.getCompilationMode());
    }
  }

  /**
   * Returns the immutable list of linker options for mostly statically linked outputs. Does not
   * include command-line options passed via --linkopt or --linkopts.
   *
   * @param config the CppConfiguration for this build
   * @param toolchain the c++ toolchain
   * @param sharedLib true if the output is a shared lib, false if it's an executable
   */
  public static ImmutableList<String> getMostlyStaticLinkOptions(
      CppConfiguration config,
      CcToolchainProvider toolchain,
      boolean sharedLib,
      boolean shouldStaticallyLinkCppRuntimes) {
    if (sharedLib) {
      return toolchain.getSharedLibraryLinkOptions(
          shouldStaticallyLinkCppRuntimes
              ? toolchain.getLegacyMostlyStaticSharedLinkFlags(config.getCompilationMode())
              : toolchain.getLegacyDynamicLinkFlags(config.getCompilationMode()));
    } else {
      return toolchain.getLegacyMostlyStaticLinkFlags(config.getCompilationMode());
    }
  }

  /**
   * Returns the immutable list of linker options for artifacts that are not fully or mostly
   * statically linked. Does not include command-line options passed via --linkopt or --linkopts.
   *
   * @param config the CppConfiguration for this build
   * @param toolchain the c++ toolchain
   * @param sharedLib true if the output is a shared lib, false if it's an executable
   */
  public static ImmutableList<String> getDynamicLinkOptions(
      CppConfiguration config, CcToolchainProvider toolchain, Boolean sharedLib) {
    if (sharedLib) {
      return toolchain.getSharedLibraryLinkOptions(
          toolchain.getLegacyDynamicLinkFlags(config.getCompilationMode()));
    } else {
      return toolchain.getLegacyDynamicLinkFlags(config.getCompilationMode());
    }
  }

  public static NestedSet<Pair<String, String>> getCoverageEnvironmentIfNeeded(
      RuleContext ruleContext, CcToolchainProvider toolchain) {
    if (ruleContext.getConfiguration().isCodeCoverageEnabled()) {
      return toolchain.getCoverageEnvironment();
    } else {
      return NestedSetBuilder.emptySet(Order.COMPILE_ORDER);
    }
  }

  public static NestedSet<Artifact> getGcovFilesIfNeeded(
      RuleContext ruleContext, CcToolchainProvider toolchain) {
    if (ruleContext.getConfiguration().isCodeCoverageEnabled()) {
      return toolchain.getCoverageFiles();
    } else {
      return NestedSetBuilder.emptySet(Order.STABLE_ORDER);
    }
  }

  /**
   * This almost trivial method looks up the default cc toolchain attribute on the rule context,
   * makes sure that it refers to a rule that has a {@link CcToolchainProvider} (gives an error
   * otherwise), and returns a reference to that {@link CcToolchainProvider}. The method only
   * returns {@code null} if there is no such attribute (this is currently not an error).
   *
   * <p>Be careful to provide explicit attribute name if the rule doesn't store cc_toolchain under
   * the default name.
   */
  @Nullable
  public static CcToolchainProvider getToolchainUsingDefaultCcToolchainAttribute(
      RuleContext ruleContext) {
    CcToolchainProvider defaultToolchain =
        getToolchain(ruleContext, CcToolchain.CC_TOOLCHAIN_DEFAULT_ATTRIBUTE_NAME);
    if (defaultToolchain != null) {
      return defaultToolchain;
    }
    return getToolchain(ruleContext, CcToolchain.CC_TOOLCHAIN_DEFAULT_ATTRIBUTE_NAME_FOR_STARLARK);
  }

  /**
   * Convenience function for finding the dynamic runtime inputs for the current toolchain. Useful
   * for non C++ rules that link against the C++ runtime.
   */
  public static NestedSet<Artifact> getDefaultCcToolchainDynamicRuntimeInputs(
      RuleContext ruleContext) throws RuleErrorException {
    CcToolchainProvider defaultToolchain =
        getToolchainUsingDefaultCcToolchainAttribute(ruleContext);
    if (defaultToolchain == null) {
      return NestedSetBuilder.emptySet(Order.STABLE_ORDER);
    }
    FeatureConfiguration featureConfiguration =
        CcCommon.configureFeaturesOrReportRuleError(ruleContext, defaultToolchain);
    return defaultToolchain.getDynamicRuntimeLinkInputs(ruleContext, featureConfiguration);
  }

  /**
   * Convenience function for finding the static runtime inputs for the current toolchain. Useful
   * for non C++ rules that link against the C++ runtime.
   */
  public static NestedSet<Artifact> getDefaultCcToolchainStaticRuntimeInputs(
      RuleContext ruleContext) throws RuleErrorException {
    CcToolchainProvider defaultToolchain =
        getToolchainUsingDefaultCcToolchainAttribute(ruleContext);
    if (defaultToolchain == null) {
      return NestedSetBuilder.emptySet(Order.STABLE_ORDER);
    }
    FeatureConfiguration featureConfiguration =
        CcCommon.configureFeaturesOrReportRuleError(ruleContext, defaultToolchain);
    return defaultToolchain.getStaticRuntimeLinkInputs(ruleContext, featureConfiguration);
  }

  /**
   * Makes sure that the given info collection has a {@link CcToolchainProvider} (gives an error
   * otherwise), and returns a reference to that {@link CcToolchainProvider}. The method will only
   * return {@code null}, if the toolchain attribute is undefined for the rule class.
   */
  @Nullable
  public static CcToolchainProvider getToolchain(
      RuleContext ruleContext, String toolchainAttribute) {
    if (!ruleContext.isAttrDefined(toolchainAttribute, LABEL)) {
      // TODO(bazel-team): Report an error or throw an exception in this case.
      return null;
    }
    TransitiveInfoCollection dep = ruleContext.getPrerequisite(toolchainAttribute, Mode.TARGET);
    return getToolchain(ruleContext, dep);
  }

  /** Returns the c++ toolchain type, or null if it is not specified on the rule class. */
  public static Label getToolchainTypeFromRuleClass(RuleContext ruleContext) {
    Label toolchainType;
    // TODO(b/65835260): Remove this conditional once j2objc can learn the toolchain type.
    if (ruleContext.attributes().has(CcToolchain.CC_TOOLCHAIN_TYPE_ATTRIBUTE_NAME)) {
      toolchainType =
          ruleContext.attributes().get(CcToolchain.CC_TOOLCHAIN_TYPE_ATTRIBUTE_NAME, NODEP_LABEL);
    } else {
      toolchainType = null;
    }
    return toolchainType;
  }

  /**
   * Makes sure that the given info collection has a {@link CcToolchainProvider} (gives an error
   * otherwise), and returns a reference to that {@link CcToolchainProvider}. The method never
   * returns {@code null}, even if there is no toolchain.
   */
  public static CcToolchainProvider getToolchain(
      RuleContext ruleContext, TransitiveInfoCollection dep) {

    Label toolchainType = getToolchainTypeFromRuleClass(ruleContext);
    if (toolchainType != null && useToolchainResolution(ruleContext)) {
      return getToolchainFromPlatformConstraints(ruleContext, toolchainType);
    }
    return getToolchainFromCrosstoolTop(ruleContext, dep);
  }

  private static CcToolchainProvider getToolchainFromPlatformConstraints(
      RuleContext ruleContext, Label toolchainType) {
    return (CcToolchainProvider) ruleContext.getToolchainContext().forToolchainType(toolchainType);
  }

  private static CcToolchainProvider getToolchainFromCrosstoolTop(
      RuleContext ruleContext, TransitiveInfoCollection dep) {
    // TODO(bazel-team): Consider checking this generally at the attribute level.
    if ((dep == null) || (dep.get(ToolchainInfo.PROVIDER) == null)) {
      ruleContext.ruleError("The selected C++ toolchain is not a cc_toolchain rule");
      return CcToolchainProvider.EMPTY_TOOLCHAIN_IS_ERROR;
    }
    return (CcToolchainProvider) dep.get(ToolchainInfo.PROVIDER);
  }

  /** Returns the directory where object files are created. */
  public static PathFragment getObjDirectory(Label ruleLabel, boolean usePic) {
    if (usePic) {
      return AnalysisUtils.getUniqueDirectory(ruleLabel, PIC_OBJS);
    } else {
      return AnalysisUtils.getUniqueDirectory(ruleLabel, OBJS);
    }
  }

  /** Returns the directory where object files are created. */
  public static PathFragment getObjDirectory(Label ruleLabel) {
    return getObjDirectory(ruleLabel, false);
  }

  /**
   * Returns a function that gets the C++ runfiles from a {@link TransitiveInfoCollection} or the
   * empty runfiles instance if it does not contain that provider.
   */
  public static final Function<TransitiveInfoCollection, Runfiles> runfilesFunction(
      RuleContext ruleContext, boolean linkingStatically) {
    final Function<TransitiveInfoCollection, Runfiles> runfilesForLinkingDynamically =
        input -> {
          CcInfo provider = input.get(CcInfo.PROVIDER);
          if (provider == null) {
            return Runfiles.EMPTY;
          } else {
            // Cannot add libraries directly because the nested set has link order.
            NestedSet<Artifact> dynamicLibrariesForRuntime =
                NestedSetBuilder.<Artifact>stableOrder()
                    .addAll(
                        provider
                            .getCcLinkingContext()
                            .getDynamicLibrariesForRuntime(/* linkingStatically= */ false))
                    .build();
            return new Runfiles.Builder(ruleContext.getWorkspaceName())
                .addTransitiveArtifacts(dynamicLibrariesForRuntime)
                .build();
          }
        };

    final Function<TransitiveInfoCollection, Runfiles> runfilesForLinkingStatically =
        input -> {
          CcInfo provider = input.get(CcInfo.PROVIDER);
          if (provider == null) {
            return Runfiles.EMPTY;
          } else {
            // Cannot add libraries directly because the nested set has link order.
            NestedSet<Artifact> dynamicLibrariesForRuntime =
                NestedSetBuilder.<Artifact>stableOrder()
                    .addAll(
                        provider
                            .getCcLinkingContext()
                            .getDynamicLibrariesForRuntime(/* linkingStatically= */ true))
                    .build();
            return new Runfiles.Builder(ruleContext.getWorkspaceName())
                .addTransitiveArtifacts(dynamicLibrariesForRuntime)
                .build();
          }
        };
    return linkingStatically ? runfilesForLinkingStatically : runfilesForLinkingDynamically;
  }

  /**
   * Returns the linked artifact.
   *
   * @param ruleContext the ruleContext to be used to scope the artifact
   * @param config the configuration to be used to scope the artifact
   * @param linkType the type of artifact, used to determine extension
   */
  public static Artifact getLinkedArtifact(
      RuleContext ruleContext,
      CcToolchainProvider ccToolchain,
      BuildConfiguration config,
      LinkTargetType linkType)
      throws RuleErrorException {
    return getLinkedArtifact(
        ruleContext, ccToolchain, config, linkType, /* linkedArtifactNameSuffix= */ "");
  }

  /** Returns the linked artifact with the given suffix. */
  public static Artifact getLinkedArtifact(
      RuleContext ruleContext,
      CcToolchainProvider ccToolchain,
      BuildConfiguration config,
      LinkTargetType linkType,
      String linkedArtifactNameSuffix)
      throws RuleErrorException {
    PathFragment name = PathFragment.create(ruleContext.getLabel().getName());
    try {
      name =
          name.replaceName(
              getArtifactNameForCategory(
                  ruleContext,
                  ccToolchain,
                  linkType.getLinkerOutput(),
                  name.getBaseName()
                      + linkedArtifactNameSuffix
                      + linkType.getPicExtensionWhenApplicable()));
    } catch (RuleErrorException e) {
      ruleContext.throwWithRuleError("Cannot get linked artifact name: " + e.getMessage());
    }

    return ruleContext.getPackageRelativeArtifact(
        name, config.getBinDirectory(ruleContext.getRule().getRepository()));
  }

  public static Artifact getLinuxLinkedArtifact(
      Label label,
      ActionConstructionContext actionConstructionContext,
      BuildConfiguration config,
      LinkTargetType linkType,
      String linkedArtifactNameSuffix) {
    PathFragment name = PathFragment.create(label.getName());
    if (linkType != LinkTargetType.EXECUTABLE) {
      name =
          name.replaceName(
              "lib"
                  + name.getBaseName()
                  + linkedArtifactNameSuffix
                  + linkType.getPicExtensionWhenApplicable()
                  + linkType.getDefaultExtension());
    }

    return actionConstructionContext.getPackageRelativeArtifact(
        name, config.getBinDirectory(label.getPackageIdentifier().getRepository()));
  }

  /**
   * Emits a warning on the rule if there are identical linkstamp artifacts with different {@code
   * CcCompilationContext}s.
   */
  public static void checkLinkstampsUnique(
      RuleErrorConsumer listener, Iterable<Linkstamp> linkstamps) {
    Map<Artifact, NestedSet<Artifact>> result = new LinkedHashMap<>();
    for (Linkstamp pair : linkstamps) {
      Artifact artifact = pair.getArtifact();
      if (result.containsKey(artifact)) {
        listener.ruleWarning(
            "rule inherits the '"
                + artifact.toDetailString()
                + "' linkstamp file from more than one cc_library rule");
      }
      result.put(artifact, pair.getDeclaredIncludeSrcs());
    }
  }

  // TODO(bazel-team): figure out a way to merge these 2 methods. See the Todo in
  // CcCommonConfiguredTarget.noCoptsMatches().

  /** Returns whether binaries must be compiled with position independent code. */
  public static boolean usePicForBinaries(
      CcToolchainProvider toolchain, FeatureConfiguration featureConfiguration) {
    // TODO(b/124030770): Please do not use this feature without contacting the C++ rules team at
    // bazel-team@google.com. The feature will be removed in a later Bazel release and it might
    // break you. Contact us so we can find alternatives for your build.
    if (featureConfiguration.getRequestedFeatures().contains("coptnopic")) {
      return false;
    }
    return toolchain.getCppConfiguration().forcePic()
        || (toolchain.usePicForDynamicLibraries(featureConfiguration)
            && toolchain.getCppConfiguration().getCompilationMode() != CompilationMode.OPT);
  }

  /**
   * Creates a CppModuleMap object for pure c++ builds. The module map artifact becomes a candidate
   * input to a CppCompileAction.
   */
  public static CppModuleMap createDefaultCppModuleMap(
      ActionConstructionContext actionConstructionContext,
      BuildConfiguration configuration,
      Label label,
      String suffix) {
    // Create the module map artifact as a genfile.
    Artifact mapFile =
        actionConstructionContext.getPackageRelativeArtifact(
            PathFragment.create(
                label.getName()
                    + suffix
                    + Iterables.getOnlyElement(CppFileTypes.CPP_MODULE_MAP.getExtensions())),
            configuration.getGenfilesDirectory(label.getPackageIdentifier().getRepository()));
    return new CppModuleMap(mapFile, label.toString());
  }

  /**
   * Returns a middleman for all files to build for the given configured target, substituting shared
   * library artifacts with corresponding solib symlinks. If multiple calls are made, then it
   * returns the same artifact for configurations with the same internal directory.
   *
   * <p>The resulting middleman only aggregates the inputs and must be expanded before populating
   * the set of files necessary to execute an action.
   */
  static List<Artifact> getAggregatingMiddlemanForCppRuntimes(
      RuleContext ruleContext,
      String purpose,
      Iterable<Artifact> artifacts,
      String solibDir,
      String solibDirOverride,
      BuildConfiguration configuration) {
    return getMiddlemanInternal(
        ruleContext,
        ruleContext.getActionOwner(),
        purpose,
        artifacts,
        true,
        true,
        solibDir,
        solibDirOverride,
        configuration);
  }

  @VisibleForTesting
  public static List<Artifact> getAggregatingMiddlemanForTesting(
      RuleContext ruleContext,
      ActionOwner owner,
      String purpose,
      Iterable<Artifact> artifacts,
      boolean useSolibSymlinks,
      String solibDir,
      BuildConfiguration configuration) {
    return getMiddlemanInternal(
        ruleContext,
        owner,
        purpose,
        artifacts,
        useSolibSymlinks,
        false,
        solibDir,
        null,
        configuration);
  }

  /** Internal implementation for getAggregatingMiddlemanForCppRuntimes. */
  private static List<Artifact> getMiddlemanInternal(
      RuleContext ruleContext,
      ActionOwner actionOwner,
      String purpose,
      Iterable<Artifact> artifacts,
      boolean useSolibSymlinks,
      boolean isCppRuntime,
      String solibDir,
      String solibDirOverride,
      BuildConfiguration configuration) {
    MiddlemanFactory factory = ruleContext.getAnalysisEnvironment().getMiddlemanFactory();
    if (useSolibSymlinks) {
      List<Artifact> symlinkedArtifacts = new ArrayList<>();
      for (Artifact artifact : artifacts) {
        Preconditions.checkState(Link.SHARED_LIBRARY_FILETYPES.matches(artifact.getFilename()));
        symlinkedArtifacts.add(
            isCppRuntime
                ? SolibSymlinkAction.getCppRuntimeSymlink(
                    ruleContext, artifact, solibDir, solibDirOverride, configuration)
                : SolibSymlinkAction.getDynamicLibrarySymlink(
                    /* actionRegistry= */ ruleContext,
                    /* actionConstructionContext= */ ruleContext,
                    solibDir,
                    artifact,
                    /* preserveName= */ false,
                    /* prefixConsumer= */ true,
                    configuration));
      }
      artifacts = symlinkedArtifacts;
      purpose += "_with_solib";
    }
    return ImmutableList.of(
        factory.createMiddlemanAllowMultiple(
            ruleContext.getAnalysisEnvironment(),
            actionOwner,
            ruleContext.getPackageDirectory(),
            purpose,
            artifacts,
            configuration.getMiddlemanDirectory(ruleContext.getRule().getRepository())));
  }

  /** Returns the FDO build subtype. */
  public static String getFdoBuildStamp(
      CppConfiguration cppConfiguration,
      FdoContext fdoContext,
      FeatureConfiguration featureConfiguration) {
    FdoContext.BranchFdoProfile branchFdoProfile = fdoContext.getBranchFdoProfile();
    if (branchFdoProfile != null) {

      if (branchFdoProfile.isAutoFdo()) {
        return featureConfiguration.isEnabled(CppRuleClasses.AUTOFDO) ? "AFDO" : null;
      }
      if (branchFdoProfile.isAutoXBinaryFdo()) {
        return featureConfiguration.isEnabled(CppRuleClasses.XBINARYFDO) ? "XFDO" : null;
      }
    }
    if (cppConfiguration.isFdo()) {
      return "FDO";
    }
    return null;
  }

  /** Creates an action to strip an executable. */
  public static void createStripAction(
      RuleContext context,
      CcToolchainProvider toolchain,
      CppConfiguration cppConfiguration,
      Artifact input,
      Artifact output,
      FeatureConfiguration featureConfiguration) {
    if (featureConfiguration.isEnabled(CppRuleClasses.NO_STRIPPING)) {
      context.registerAction(
          SymlinkAction.toArtifact(
              context.getActionOwner(),
              input,
              output,
              "Symlinking original binary as stripped binary"));
      return;
    }

    if (!featureConfiguration.actionIsConfigured(CppActionNames.STRIP)) {
      context.ruleError("Expected action_config for 'strip' to be configured.");
      return;
    }

    CcToolchainVariables variables =
        new CcToolchainVariables.Builder(toolchain.getBuildVariables())
            .addStringVariable(
                StripBuildVariables.OUTPUT_FILE.getVariableName(), output.getExecPathString())
            .addStringSequenceVariable(
                StripBuildVariables.STRIPOPTS.getVariableName(), cppConfiguration.getStripOpts())
            .addStringVariable(CcCommon.INPUT_FILE_VARIABLE_NAME, input.getExecPathString())
            .build();
    ImmutableList<String> commandLine =
        ImmutableList.copyOf(featureConfiguration.getCommandLine(CppActionNames.STRIP, variables));
    ImmutableMap.Builder<String, String> executionInfoBuilder = ImmutableMap.builder();
    for (String executionRequirement :
        featureConfiguration.getToolRequirementsForAction(CppActionNames.STRIP)) {
      executionInfoBuilder.put(executionRequirement, "");
    }
    Action[] stripAction =
        new SpawnAction.Builder()
            .addInput(input)
            .addTransitiveInputs(toolchain.getStripFiles())
            .addOutput(output)
            .useDefaultShellEnvironment()
            .setExecutable(
                PathFragment.create(
                    featureConfiguration.getToolPathForAction(CppActionNames.STRIP)))
            .setExecutionInfo(executionInfoBuilder.build())
            .setProgressMessage("Stripping %s for %s", output.prettyPrint(), context.getLabel())
            .setMnemonic("CcStrip")
            .addCommandLine(CustomCommandLine.builder().addAll(commandLine).build())
            .build(context);
    context.registerAction(stripAction);
  }

  public static void maybeAddStaticLinkMarkerProvider(
      RuleConfiguredTargetBuilder builder, RuleContext ruleContext) {
    if (ruleContext.getFeatures().contains("fully_static_link")) {
      builder.add(StaticallyLinkedMarkerProvider.class, new StaticallyLinkedMarkerProvider(true));
    }
  }

  static Artifact getCompileOutputArtifact(
      ActionConstructionContext actionConstructionContext,
      Label label,
      String outputName,
      BuildConfiguration config) {
    PathFragment objectDir = getObjDirectory(label);
    return actionConstructionContext.getDerivedArtifact(
        objectDir.getRelative(outputName),
        config.getBinDirectory(label.getPackageIdentifier().getRepository()));
  }

  /** Returns the corresponding compiled TreeArtifact given the source TreeArtifact. */
  public static SpecialArtifact getCompileOutputTreeArtifact(
      ActionConstructionContext actionConstructionContext,
      Label label,
      Artifact sourceTreeArtifact,
      String outputName,
      boolean usePic) {
    return actionConstructionContext.getTreeArtifact(
        getObjDirectory(label, usePic).getRelative(outputName), sourceTreeArtifact.getRoot());
  }

  public static String getArtifactNameForCategory(
      RuleErrorConsumer ruleErrorConsumer,
      CcToolchainProvider toolchain,
      ArtifactCategory category,
      String outputName)
      throws RuleErrorException {
    try {
      return toolchain.getFeatures().getArtifactNameForCategory(category, outputName);
    } catch (EvalException e) {
      ruleErrorConsumer.throwWithRuleError(e.getMessage());
      throw new IllegalStateException("Should not be reached");
    }
  }

  static String getDotdFileName(
      RuleErrorConsumer ruleErrorConsumer,
      CcToolchainProvider toolchain,
      ArtifactCategory outputCategory,
      String outputName)
      throws RuleErrorException {
    String baseName =
        outputCategory == ArtifactCategory.OBJECT_FILE
                || outputCategory == ArtifactCategory.PROCESSED_HEADER
            ? outputName
            : getArtifactNameForCategory(ruleErrorConsumer, toolchain, outputCategory, outputName);

    return getArtifactNameForCategory(
        ruleErrorConsumer, toolchain, ArtifactCategory.INCLUDED_FILE_LIST, baseName);
  }

  /**
   * Returns true when {@link CppRuleClasses#WINDOWS_EXPORT_ALL_SYMBOLS} feature is enabled and
   * {@link CppRuleClasses#NO_WINDOWS_EXPORT_ALL_SYMBOLS} feature is not enabled and no custom DEF
   * file is specified in win_def_file attribute.
   */
  public static boolean shouldUseGeneratedDefFile(
      RuleContext ruleContext, FeatureConfiguration featureConfiguration) {
    return featureConfiguration.isEnabled(CppRuleClasses.WINDOWS_EXPORT_ALL_SYMBOLS)
        && !featureConfiguration.isEnabled(CppRuleClasses.NO_WINDOWS_EXPORT_ALL_SYMBOLS)
        && ruleContext.getPrerequisiteArtifact("win_def_file", Mode.TARGET) == null;
  }

  /**
   * Create actions for parsing object files to generate a DEF file, should only be used when
   * targeting Windows.
   *
   * @param defParser The tool we use to parse object files for generating the DEF file.
   * @param objectFiles A list of object files to parse
   * @param dllName The DLL name to be written into the DEF file, it specifies which DLL is required
   *     at runtime
   * @return The DEF file artifact.
   */
  public static Artifact createDefFileActions(
      RuleContext ruleContext,
      Artifact defParser,
      ImmutableList<Artifact> objectFiles,
      String dllName) {
    Artifact defFile =
        ruleContext.getBinArtifact(
            ruleContext.getLabel().getName()
                + ".gen"
                + Iterables.getOnlyElement(CppFileTypes.WINDOWS_DEF_FILE.getExtensions()));
    CustomCommandLine.Builder argv = new CustomCommandLine.Builder();
    for (Artifact objectFile : objectFiles) {
      argv.addDynamicString(objectFile.getExecPathString());
    }

    ruleContext.registerAction(
        new SpawnAction.Builder()
            .addInputs(objectFiles)
            .addOutput(defFile)
            .setExecutable(defParser)
            .useDefaultShellEnvironment()
            .addCommandLine(
                CustomCommandLine.builder().addExecPath(defFile).addDynamicString(dllName).build())
            .addCommandLine(
                argv.build(),
                ParamFileInfo.builder(ParameterFile.ParameterFileType.SHELL_QUOTED)
                    .setCharset(UTF_8)
                    .setUseAlways(true)
                    .build())
            .setMnemonic("DefParser")
            .build(ruleContext));
    return defFile;
  }

  /**
   * Returns true if the build implied by the given config and toolchain uses --start-lib/--end-lib
   * ld options.
   */
  public static boolean useStartEndLib(
      CppConfiguration config,
      CcToolchainProvider toolchain,
      FeatureConfiguration featureConfiguration) {
    return config.startEndLibIsRequested() && toolchain.supportsStartEndLib(featureConfiguration);
  }

  /**
   * Returns the type of archives being used by the build implied by the given config and toolchain.
   */
  public static Link.ArchiveType getArchiveType(
      CppConfiguration config,
      CcToolchainProvider toolchain,
      FeatureConfiguration featureConfiguration) {
    return useStartEndLib(config, toolchain, featureConfiguration)
        ? Link.ArchiveType.START_END_LIB
        : Link.ArchiveType.REGULAR;
  }

  /**
   * Returns true if interface shared objects should be used in the build implied by the given
   * cppConfiguration and toolchain.
   */
  public static boolean useInterfaceSharedLibraries(
      CppConfiguration cppConfiguration,
      CcToolchainProvider toolchain,
      FeatureConfiguration featureConfiguration) {
    return toolchain.supportsInterfaceSharedLibraries(featureConfiguration)
        && cppConfiguration.getUseInterfaceSharedLibraries();
  }

  public static CcNativeLibraryProvider collectNativeCcLibraries(
      List<? extends TransitiveInfoCollection> deps, List<LibraryToLink> libraries) {
    NestedSetBuilder<LibraryToLink> result = NestedSetBuilder.linkOrder();
    result.addAll(libraries);
    for (CcNativeLibraryProvider dep :
        AnalysisUtils.getProviders(deps, CcNativeLibraryProvider.class)) {
      result.addTransitive(dep.getTransitiveCcNativeLibraries());
    }
    return new CcNativeLibraryProvider(result.build());
  }

  public static void checkProtoLibrariesInDeps(
      RuleErrorConsumer ruleErrorConsumer, Iterable<TransitiveInfoCollection> deps) {
    for (TransitiveInfoCollection dep : deps) {
      if (dep.get(ProtoInfo.PROVIDER) != null && dep.get(CcInfo.PROVIDER) == null) {
        ruleErrorConsumer.attributeError(
            "deps",
            String.format("proto_library '%s' does not produce output for C++", dep.getLabel()));
      }
    }
  }

  static boolean useToolchainResolution(RuleContext ruleContext) {
    CppOptions cppOptions =
        Preconditions.checkNotNull(
            ruleContext.getConfiguration().getOptions().get(CppOptions.class));

    if (cppOptions.enableCcToolchainResolution) {
      return true;
    }

    // TODO(https://github.com/bazelbuild/bazel/issues/7260): Remove this and the flag.
    PlatformConfiguration platformConfig =
        Preconditions.checkNotNull(ruleContext.getFragment(PlatformConfiguration.class));
    return platformConfig.isToolchainTypeEnabled(getToolchainTypeFromRuleClass(ruleContext));
  }

  public static ImmutableList<CcCompilationContext> getCompilationContextsFromDeps(
      List<TransitiveInfoCollection> deps) {
    return Streams.stream(AnalysisUtils.getProviders(deps, CcInfo.PROVIDER))
        .map(CcInfo::getCcCompilationContext)
        .collect(ImmutableList.toImmutableList());
  }

  public static ImmutableList<CcLinkingContext> getLinkingContextsFromDeps(
      ImmutableList<TransitiveInfoCollection> deps) {
    return Streams.stream(AnalysisUtils.getProviders(deps, CcInfo.PROVIDER))
        .map(CcInfo::getCcLinkingContext)
        .collect(ImmutableList.toImmutableList());
  }

  public static Artifact getGrepIncludes(RuleContext ruleContext) {
    return ruleContext.attributes().has("$grep_includes")
        ? ruleContext.getPrerequisiteArtifact("$grep_includes", Mode.HOST)
        : null;
  }

  public static boolean doNotSplitLinkingCmdLine(
      StarlarkSemantics starlarkSemantics, CcToolchainProvider ccToolchain) {
    return starlarkSemantics.incompatibleDoNotSplitLinkingCmdline()
        || ccToolchain.doNotSplitLinkingCmdline();
  }
}
