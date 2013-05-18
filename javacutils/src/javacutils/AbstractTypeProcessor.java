package javacutils;

import java.util.HashSet;
import java.util.Set;

import javax.annotation.processing.*;
import javax.lang.model.element.Name;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.ElementFilter;

import com.sun.tools.javac.main.JavaCompiler;
import com.sun.tools.javac.comp.CompileStates.CompileState;
import com.sun.tools.javac.processing.JavacProcessingEnvironment;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.Log;

import com.sun.source.tree.ClassTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;

/**
 * This class is an abstract annotation processor designed to be a
 * convenient superclass for concrete "type processors", processors that
 * require the type information in the processed source.
 *
 * <p>Type processing occurs in one round after the tool (e.g. Java compiler)
 * analyzes the source (all sources taken as input to the tool and sources
 * generated by other annotation processors).
 *
 * <p>The tool infrastructure will interact with classes extending this abstract
 * class as follows:
 *
 * <ol>
 * [1-3: Identical to {@link Processor} life cycle]
 *
 * <li>If an existing {@code Processor} object is not being used, to
 * create an instance of a processor the tool calls the no-arg
 * constructor of the processor class.
 *
 * <li>Next, the tool calls the {@link #init init} method with
 * an appropriate {@code ProcessingEnvironment}.
 *
 * <li>Afterwards, the tool calls {@link #getSupportedAnnotationTypes
 * getSupportedAnnotationTypes}, {@link #getSupportedOptions
 * getSupportedOptions}, and {@link #getSupportedSourceVersion
 * getSupportedSourceVersion}.  These methods are only called once per
 * run, not on each round.
 *
 * [4-5: Unique to {@code AbstractTypeProcessor} subclasses]
 *
 * <li>For each class containing a supported annotation, the tool calls
 * {@link #typeProcess(TypeElement, TreePath) typeProcess} method on the
 * {@code Processor}.  The class is guaranteed to be type-checked Java code
 * and all the tree type and symbol information is resolved.
 *
 * <li>Finally, the tools calls the
 * {@link #typeProcessingOver() typeProcessingOver} method
 * on the {@code Processor}.
 *
 * </ol>
 *
 * <p>The tool is permitted to ask type processors to process a class once
 * it is analyzed before the rest of classes are analyzed.  The tool is also
 * permitted to stop type processing immediately if any errors are raised,
 * without invoking {@code typeProcessingOver}
 *
 * <p>A subclass may override any of the methods in this class, as long as the
 * general {@link javax.annotation.processing.Processor Processor}
 * contract is obeyed, with one notable exception.
 * {@link #process(Set, RoundEnvironment)} may not be overridden, as it
 * is called during the declaration annotation phase before classes are analyzed.
 *
 * @author Mahmood Ali
 * @author Werner Dietl
 */
public abstract class AbstractTypeProcessor extends AbstractProcessor {
    /**
     * The set of fully-qualified element names that should be type checked.
     * We store the names of the elements, in order to prevent
     * possible confusion between different Element instantiations.
     */
    private final Set<Name> elements = new HashSet<Name>();

    /**
     * Method {@link #typeProcessingStart()} must be invoked exactly once,
     * before any invocation of {@link #typeProcess(TypeElement, TreePath)}.
     */
    private boolean hasInvokedTypeProcessingStart = false;

    /**
     * Method {@link #typeProcessingOver()} must be invoked exactly once,
     * after the last invocation of {@link #typeProcess(TypeElement, TreePath)}.
     */
    private static boolean hasInvokedTypeProcessingOver = false;

    /**
     * The TaskListener registered for completion of attribution.
     */
    private final AttributionTaskListener listener = new AttributionTaskListener();

    /**
     * Constructor for subclasses to call.
     */
    protected AbstractTypeProcessor() { }

    /**
     * {@inheritDoc}
     *
     * Register a TaskListener that will get called after FLOW.
     */
    @Override
    public void init(ProcessingEnvironment env) {
        super.init(env);
        JavacTask.instance(env).addTaskListener(listener);
        Context ctx = ((JavacProcessingEnvironment) processingEnv).getContext();
        JavaCompiler compiler = JavaCompiler.instance(ctx);
        compiler.shouldStopPolicyIfNoError = CompileState.max(compiler.shouldStopPolicyIfNoError,
                                                           CompileState.FLOW);
    }

    /**
     * The use of this method is obsolete in type processors.  The method is
     * called during declaration annotation processing phase only.
     * It registers the names of elements to process.
     */
    @Override
    public final boolean process(Set<? extends TypeElement> annotations,
            RoundEnvironment roundEnv) {
        for (TypeElement elem : ElementFilter.typesIn(roundEnv.getRootElements())) {
            elements.add(elem.getQualifiedName());
        }
        return false;
    }

    /**
     * A method to be called once before the first call to typeProcess.
     *
     * <p>Subclasses may override this method to do any initialization work.
     */
    public void typeProcessingStart() {}

    /**
     * Processes a fully analyzed class that contains a supported annotation
     * (look {@link #getSupportedAnnotationTypes()}).
     *
     * <p>The passed class is always valid type-checked Java code.
     *
     * @param element       element of the analyzed class
     * @param tree  the tree path to the element, with the leaf being a
     *              {@link ClassTree}
     */
    public abstract void typeProcess(TypeElement element, TreePath tree);

    /**
     * A method to be called once all the classes are processed and no error
     * is reported.
     *
     * <p>Subclasses may override this method to do any aggregate analysis
     * (e.g. generate report, persistence) or resource deallocation.
     *
     * <p>If an error (a Java error or a processor error) is reported, this
     * method is not guaranteed to be invoked.
     */
    public void typeProcessingOver() { }

    /**
     * A task listener that invokes the processor whenever a class is fully
     * analyzed.
     */
    private final class AttributionTaskListener implements TaskListener {

        @Override
        public void finished(TaskEvent e) {
            if (e.getKind() != TaskEvent.Kind.ANALYZE)
                return;

            if (!hasInvokedTypeProcessingStart) {
                typeProcessingStart();
                hasInvokedTypeProcessingStart = true;
            }

            Log log = Log.instance(((JavacProcessingEnvironment) processingEnv).getContext());

            if (!hasInvokedTypeProcessingOver && elements.isEmpty() && log.nerrors == 0) {
                typeProcessingOver();
                hasInvokedTypeProcessingOver = true;
            }

            if (e.getTypeElement() == null)
                throw new AssertionError("event task without a type element");
            if (e.getCompilationUnit() == null)
                throw new AssertionError("event task without compilation unit");

            if (!elements.remove(e.getTypeElement().getQualifiedName()))
                return;

            TypeElement elem = e.getTypeElement();
            TreePath p = Trees.instance(processingEnv).getPath(elem);

            typeProcess(elem, p);

            if (!hasInvokedTypeProcessingOver && elements.isEmpty() && log.nerrors == 0) {
                typeProcessingOver();
                hasInvokedTypeProcessingOver = true;
            }
        }

        @Override
        public void started(TaskEvent e) { }
    }
}
