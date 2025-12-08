#!/usr/bin/env python3
"""
Pipeline automation script that runs the data processing programs sequentially.
"""

import subprocess
import shutil
import os
from pathlib import Path


def run_command(cmd: list, cwd: str = None, input_text: str = None) -> bool:
    """Run a command and return True if successful."""
    print(f"\n{'='*60}")
    print(f"Running: {' '.join(cmd)}")
    if cwd:
        print(f"Working directory: {cwd}")
    print('='*60)
    
    try:
        result = subprocess.run(
            cmd,
            cwd=cwd,
            input=input_text,
            text=True,
            capture_output=True
        )
        print(result.stdout)
        if result.stderr:
            print(f"STDERR: {result.stderr}")
        
        if result.returncode != 0:
            print(f"ERROR: Command failed with return code {result.returncode}")
            return False
        return True
    except Exception as e:
        print(f"ERROR: {e}")
        return False


def rename_file(src: Path, dst: Path) -> bool:
    """Rename a file from src to dst."""
    print(f"\n{'='*60}")
    print(f"Renaming: {src} -> {dst}")
    print('='*60)
    
    try:
        if dst.exists():
            dst.unlink()
        src.rename(dst)
        print(f"Successfully renamed to {dst}")
        return True
    except Exception as e:
        print(f"ERROR: Failed to rename file: {e}")
        return False


def copy_file(src: Path, dst: Path) -> bool:
    """Copy a file from src to dst."""
    print(f"\n{'='*60}")
    print(f"Copying: {src} -> {dst}")
    print('='*60)
    
    try:
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src, dst)
        print(f"Successfully copied to {dst}")
        return True
    except Exception as e:
        print(f"ERROR: Failed to copy file: {e}")
        return False


def main():
    # Base directory (where this script is located)
    base_dir = Path(__file__).resolve().parent
    
    # Contract number to use
    contract_number = "381034"
    
    print("\n" + "="*70)
    print("PIPELINE AUTOMATION SCRIPT")
    print(f"Contract Number: {contract_number}")
    print("="*70)
    
    # Step 1: Run cds_mapping.py with contract number input
    print("\n\n" + "#"*70)
    print("# STEP 1: Running cds_mapping.py")
    print("#"*70)
    
    cds_folder = base_dir / "cds data population"
    cds_script = cds_folder / "cds_mapping.py"
    cds_venv_python = cds_folder / ".venv" / "bin" / "python3"
    
    if not run_command(
        [str(cds_venv_python), str(cds_script)],
        cwd=str(cds_folder),
        input_text=contract_number + "\n"
    ):
        print("ERROR: Step 1 failed - cds_mapping.py")
        return 1
    
    # Step 2: Rename output.json to input.json in cds data population/output
    print("\n\n" + "#"*70)
    print("# STEP 2: Renaming output.json to input.json in cds data population/output")
    print("#"*70)
    
    cds_output_file = cds_folder / "output" / "output.json"
    cds_renamed_file = cds_folder / "output" / "input.json"
    
    if not rename_file(cds_output_file, cds_renamed_file):
        print("ERROR: Step 2 failed - renaming output.json")
        return 1
    
    # Step 3: Transfer input.json to rest of the data population/input
    print("\n\n" + "#"*70)
    print("# STEP 3: Transferring input.json to rest of the data population/input")
    print("#"*70)
    
    rest_folder = base_dir / "rest of the data population"
    rest_input_folder = rest_folder / "input"
    rest_input_file = rest_input_folder / "input.json"
    
    if not copy_file(cds_renamed_file, rest_input_file):
        print("ERROR: Step 3 failed - transferring input.json")
        return 1
    
    # Step 4: Run merge_letterdata.py
    print("\n\n" + "#"*70)
    print("# STEP 4: Running merge_letterdata.py")
    print("#"*70)
    
    merge_script = rest_folder / "merge_letterdata.py"
    
    if not run_command(
        ["python3", str(merge_script)],
        cwd=str(rest_folder)
    ):
        print("ERROR: Step 4 failed - merge_letterdata.py")
        return 1
    
    # Step 5: Rename output.json to input.json in rest of the data population/output
    print("\n\n" + "#"*70)
    print("# STEP 5: Renaming output.json to input.json in rest of the data population/output")
    print("#"*70)
    
    rest_output_file = rest_folder / "output" / "output.json"
    rest_renamed_file = rest_folder / "output" / "input.json"
    
    if not rename_file(rest_output_file, rest_renamed_file):
        print("ERROR: Step 5 failed - renaming output.json")
        return 1
    
    # Step 6: Transfer input.json to scriptResolution/src/main/resources/input
    print("\n\n" + "#"*70)
    print("# STEP 6: Transferring input.json to scriptResolution/src/main/resources/input")
    print("#"*70)
    
    script_resolution_folder = base_dir / "scriptResolution"
    script_resolution_input = script_resolution_folder / "src" / "main" / "resources" / "input"
    script_resolution_input_file = script_resolution_input / "input.json"
    
    if not copy_file(rest_renamed_file, script_resolution_input_file):
        print("ERROR: Step 6 failed - transferring input.json")
        return 1
    
    # Step 7: Compile and run ExprEval.java
    print("\n\n" + "#"*70)
    print("# STEP 7: Running ExprEval.java (using Maven wrapper)")
    print("#"*70)
    
    # Use Maven wrapper from pdfgen folder
    mvnw = base_dir / "pdfgen" / "mvnw"
    
    # First, compile the project using Maven
    if not run_command(
        [str(mvnw), "compile", "-q", "-f", str(script_resolution_folder / "pom.xml")],
        cwd=str(script_resolution_folder)
    ):
        print("ERROR: Step 7 failed - Maven compile")
        return 1
    
    # Then run the main class (exec-maven-plugin is already configured in pom.xml)
    if not run_command(
        [str(mvnw), "exec:java", "-q", "-f", str(script_resolution_folder / "pom.xml")],
        cwd=str(script_resolution_folder)
    ):
        print("ERROR: Step 7 failed - running ExprEval")
        return 1
    
    # Step 8: Rename output.json to input.json in scriptResolution/src/main/resources/output
    print("\n\n" + "#"*70)
    print("# STEP 8: Renaming output.json to input.json in scriptResolution resources/output")
    print("#"*70)
    
    script_resolution_output = script_resolution_folder / "src" / "main" / "resources" / "output"
    script_resolution_output_file = script_resolution_output / "output.json"
    script_resolution_renamed_file = script_resolution_output / "input.json"
    
    if not rename_file(script_resolution_output_file, script_resolution_renamed_file):
        print("ERROR: Step 8 failed - renaming output.json")
        return 1
    
    # Step 9: Transfer input.json to pdfgen/src/main/resources/test
    print("\n\n" + "#"*70)
    print("# STEP 9: Transferring input.json to pdfgen/src/main/resources/test")
    print("#"*70)
    
    pdfgen_test_folder = base_dir / "pdfgen" / "src" / "main" / "resources" / "testInput"
    pdfgen_input_file = pdfgen_test_folder / "input.json"
    
    if not copy_file(script_resolution_renamed_file, pdfgen_input_file):
        print("ERROR: Step 9 failed - transferring input.json")
        return 1
    
    # Step 10: Upload docx to Storage API
    print("\n\n" + "#"*70)
    print("# STEP 10: Uploading dest-with-header-footer.docx to Storage API")
    print("#"*70)
    
    docx_file = pdfgen_test_folder / "dest-with-header-footer.docx"
    storage_api_url = "https://platform.dev-capability.zinnia.com/pdfgeneration-service/storage/docxs"
    
    if not run_command(
        [
            "curl", "-X", "POST",
            "-F", f"file=@{docx_file}",
            storage_api_url
        ]
    ):
        print("ERROR: Step 10 failed - Storage API upload")
        return 1
    
    # Step 11: Call Docx-to-PDF API and save response
    print("\n\n" + "#"*70)
    print("# STEP 11: Calling Docx-to-PDF API and saving PDF output")
    print("#"*70)
    
    render_api_url = "https://platform.dev-capability.zinnia.com/pdfgeneration-service/docx/render-to-pdf?templateName=dest-with-header-footer.docx"
    
    # Create testOutput folder if it doesn't exist
    test_output_folder = base_dir / "pdfgen" / "src" / "main" / "resources" / "testOutput"
    test_output_folder.mkdir(parents=True, exist_ok=True)
    output_pdf_file = test_output_folder / "output.pdf"
    
    # Read the input.json content
    with open(pdfgen_input_file, 'r', encoding='utf-8') as f:
        json_content = f.read()
    
    print(f"\n{'='*60}")
    print(f"Calling: {render_api_url}")
    print(f"Saving response to: {output_pdf_file}")
    print('='*60)
    
    try:
        import subprocess
        result = subprocess.run(
            [
                "curl", "-X", "POST",
                "-F", f"json={json_content}",
                render_api_url,
                "-o", str(output_pdf_file)
            ],
            capture_output=True,
            text=True
        )
        print(result.stdout)
        if result.stderr:
            print(f"STDERR: {result.stderr}")
        
        if result.returncode != 0:
            print(f"ERROR: Docx-to-PDF API failed with return code {result.returncode}")
            return 1
        
        print(f"\nPDF saved to: {output_pdf_file}")
    except Exception as e:
        print(f"ERROR: Failed to call Docx-to-PDF API: {e}")
        return 1
    
    # Step 12: Stamp barcode image on each page of the PDF
    print("\n\n" + "#"*70)
    print("# STEP 12: Stamping barcode image on each page of the PDF")
    print("#"*70)
    
    stamp_api_url = "https://platform.dev-capability.zinnia.com/pdfgeneration-service/pdf/stamp-image"
    barcode_image = pdfgen_test_folder / "sample_barcode.jpg"
    
    # Get the number of pages in the PDF
    page_count = get_pdf_page_count(output_pdf_file)
    if page_count is None or page_count <= 0:
        print("ERROR: Could not determine PDF page count")
        return 1
    
    print(f"PDF has {page_count} page(s)")
    
    # Stamp each page
    for page in range(1, page_count + 1):
        print(f"\n{'='*60}")
        print(f"Stamping page {page} of {page_count}")
        print('='*60)
        
        # Create a temporary file for the output
        temp_output = test_output_folder / "output_temp.pdf"
        
        try:
            result = subprocess.run(
                [
                    "curl", "-X", "POST",
                    "-F", f"pdf=@{output_pdf_file}",
                    "-F", f"image=@{barcode_image}",
                    f"{stamp_api_url}?x=204&y=220.8&width=6.4&height=45.2&units=mm&anchor=top-left&page={page}",
                    "-o", str(temp_output)
                ],
                capture_output=True,
                text=True
            )
            
            if result.stderr:
                print(f"STDERR: {result.stderr}")
            
            if result.returncode != 0:
                print(f"ERROR: Stamp API failed for page {page} with return code {result.returncode}")
                return 1
            
            # Replace original PDF with stamped version
            shutil.move(str(temp_output), str(output_pdf_file))
            print(f"Page {page} stamped successfully")
            
        except Exception as e:
            print(f"ERROR: Failed to stamp page {page}: {e}")
            return 1
    
    print(f"\nAll {page_count} pages stamped successfully!")
    
    # Done!
    print("\n\n" + "="*70)
    print("PIPELINE COMPLETED SUCCESSFULLY!")
    print("="*70)
    print(f"\nFinal JSON location: {pdfgen_input_file}")
    print(f"Final PDF location: {output_pdf_file}")
    
    return 0


def get_pdf_page_count(pdf_path: Path) -> int:
    """Get the number of pages in a PDF file."""
    import subprocess
    import re
    
    # Try using mdls (macOS) first
    try:
        result = subprocess.run(
            ["mdls", "-name", "kMDItemNumberOfPages", str(pdf_path)],
            capture_output=True,
            text=True
        )
        if result.returncode == 0:
            # Parse output like: kMDItemNumberOfPages = 5
            match = re.search(r'kMDItemNumberOfPages\s*=\s*(\d+)', result.stdout)
            if match:
                return int(match.group(1))
    except Exception:
        pass
    
    # Try using pdfinfo (if poppler is installed)
    try:
        result = subprocess.run(
            ["pdfinfo", str(pdf_path)],
            capture_output=True,
            text=True
        )
        if result.returncode == 0:
            # Parse output like: Pages:          5
            match = re.search(r'Pages:\s*(\d+)', result.stdout)
            if match:
                return int(match.group(1))
    except Exception:
        pass
    
    # Fallback: parse PDF file directly to find page count
    try:
        with open(pdf_path, 'rb') as f:
            content = f.read()
            # Look for /Type /Page entries (not /Pages)
            # This is a simple heuristic
            count = content.count(b'/Type /Page')
            # Subtract /Type /Pages entries
            pages_count = content.count(b'/Type /Pages')
            return count - pages_count if count > pages_count else count
    except Exception:
        pass
    
    # Last resort: try to find /Count in the PDF
    try:
        with open(pdf_path, 'rb') as f:
            content = f.read().decode('latin-1')
            match = re.search(r'/Count\s+(\d+)', content)
            if match:
                return int(match.group(1))
    except Exception:
        pass
    
    return None


if __name__ == "__main__":
    exit(main())

