/* Reads one regex per line and compiles it with ICU (the engine Android uses). Exits 1 if any fails. */
#include <unicode/uregex.h>
#include <unicode/ustring.h>
#include <stdio.h>
#include <string.h>
int main(void){ int bad=0; char line[4096]; while(fgets(line,sizeof line,stdin)){ line[strcspn(line,"\n")]=0; UChar u[4096]; UErrorCode e=U_ZERO_ERROR; u_uastrcpy(u,line); UParseError pe; URegularExpression* r=uregex_open(u,-1,0,&pe,&e); if(U_FAILURE(e)){ bad=1; printf("ICU rejects regex (%s): %s\n", u_errorName(e), line);} if(r) uregex_close(r);} return bad; }
