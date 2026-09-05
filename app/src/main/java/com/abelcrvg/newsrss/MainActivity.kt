package com.abelcrvg.newsrss

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.abelcrvg.newsrss.core.feed.FeedItem
import com.abelcrvg.newsrss.core.model.*
import com.abelcrvg.newsrss.core.source.SourceRegistry
import com.abelcrvg.newsrss.data.extraction.JsoupArticleExtractor
import com.abelcrvg.newsrss.data.feed.SmartFeedReader
import com.abelcrvg.newsrss.data.source.*
import com.abelcrvg.newsrss.ui.theme.NewsRSSTheme
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class MainActivity:ComponentActivity(){override fun onCreate(b:Bundle?){super.onCreate(b);setContent{NewsRSSTheme{NewsRSSApp()}}}}

@Composable private fun NewsRSSApp(){
 val context=LocalContext.current;val sourceStore=remember{SourceStore(context.applicationContext)};val readStore=remember{ReadArticleStore(context.applicationContext)};val savedStore=remember{SavedArticleStore(context.applicationContext)}
 var sources by remember{mutableStateOf(sourceStore.load(SourceRegistry.defaultSources))};var readUrls by remember{mutableStateOf(readStore.load())};var readItems by remember{mutableStateOf(readStore.loadItems())};var savedUrls by remember{mutableStateOf(savedStore.load())};var savedItems by remember{mutableStateOf(savedStore.loadItems())}
 var items by remember{mutableStateOf<List<FeedItem>>(emptyList())};var article by remember{mutableStateOf<Article?>(null)};var currentItem by remember{mutableStateOf<FeedItem?>(null)};var loading by remember{mutableStateOf(true)};var opening by remember{mutableStateOf(false)};var error by remember{mutableStateOf<String?>(null)};var urlInput by remember{mutableStateOf("")};var sourceError by remember{mutableStateOf<String?>(null)};var selectedCategory by remember{mutableStateOf<NewsCategory?>(null)};var manageSources by remember{mutableStateOf(false)};var tab by remember{mutableIntStateOf(0)}
 val listState=rememberLazyListState();var returnIndex by remember{mutableIntStateOf(0)};var returnOffset by remember{mutableIntStateOf(0)};val scope=rememberCoroutineScope()
 fun persist(s:List<FeedSource>){sources=s;sourceStore.save(s)}
 fun markRead(x:FeedItem){readStore.markRead(x);readUrls=readUrls+x.url;readItems=readStore.loadItems()}
 fun toggleSaved(x:FeedItem){val save=x.url !in savedUrls;savedStore.setSaved(x,save);savedUrls=savedStore.load();savedItems=savedStore.loadItems()}
 fun refresh(){loading=true;error=null;scope.launch{val results=coroutineScope{sources.filter{it.enabled}.map{s->async{s to SmartFeedReader().read(s)}}.awaitAll()};val successful=results.flatMap{(s,r)->r.getOrElse{emptyList()}.map{it.copy(sourceId=s.id)}};val failures=results.filter{it.second.isFailure};items=successful.distinctBy{it.url}.sortedWith(compareByDescending<FeedItem>{it.publishedAt?:Instant.EPOCH});error=when{successful.isEmpty()->failures.firstOrNull()?.second?.exceptionOrNull()?.message?:"Nenhuma fonte conseguiu fornecer notícias.";failures.isNotEmpty()->"Algumas fontes não puderam ser atualizadas.";else->null};loading=false}}
 fun openItem(x:FeedItem){returnIndex=listState.firstVisibleItemIndex;returnOffset=listState.firstVisibleItemScrollOffset;currentItem=x;markRead(x);opening=true;error=null;scope.launch{JsoupArticleExtractor().extract(x.url).onSuccess{a->article=a.copy(publishedAt=a.publishedAt?:x.publishedAt)}.onFailure{error=it.message?:"Não foi possível abrir a notícia."};opening=false}}
 fun addSource(){sourceError=null;val n=urlInput.trim().removeSuffix("/");val u=runCatching{URI(n)}.getOrNull();if(u==null||u.scheme !in listOf("http","https")||u.host.isNullOrBlank()){sourceError="Digite uma URL válida, por exemplo: https://www.uol.com.br";return};val host=u.host.removePrefix("www.");val id="custom-${host.replace(Regex("[^a-zA-Z0-9]"),"-")}";if(sources.any{it.id==id}){sourceError="Essa fonte já está adicionada.";return};persist(sources+FeedSource(id,host.substringBefore('.').replaceFirstChar{it.uppercase()},n,category=NewsCategory.NEWS));urlInput="";refresh()}
 val visible=remember(items,sources,selectedCategory,readUrls){val base=items.filterNot{it.url in readUrls};selectedCategory?.let{c->val ids=sources.filter{it.category==c&&it.enabled}.map{it.id}.toSet();base.filter{it.sourceId in ids}}?:base}
 LaunchedEffect(Unit){refresh()}
 if(article!=null){BackHandler{article=null};ReaderContent(article!!,currentItem!!,currentItem!!.url in savedUrls,onBack={article=null},onToggleSaved={toggleSaved(currentItem!!)});return}
 LaunchedEffect(article){if(article==null&&(returnIndex>0||returnOffset>0))listState.scrollToItem(returnIndex,returnOffset)}
 if(manageSources){BackHandler{manageSources=false};SourceManager(sources,urlInput,{urlInput=it},sourceError,{addSource()},{manageSources=false},{s->persist(sources.map{if(it.id==s.id)it.copy(enabled=!it.enabled)else it})},{s,c->persist(sources.map{if(it.id==s.id)it.copy(category=c)else it})},{s->persist(sources.filterNot{it.id==s.id});refresh()});return}
 Scaffold{padding->Column(Modifier.fillMaxSize().padding(padding)){
   Column(Modifier.padding(horizontal=20.dp,vertical=14.dp)){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){Column{Text("NewsRSS",style=MaterialTheme.typography.headlineLarge);Text("${sources.count{it.enabled}} fontes ativas",style=MaterialTheme.typography.bodyMedium)};Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedButton({manageSources=true}){Text("Fontes")};Button({refresh()},enabled=!loading&&!opening){Text("Atualizar")}}}
   Spacer(Modifier.height(12.dp));Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){TabButton("Notícias",tab==0){tab=0};TabButton("Lidas (${readItems.size})",tab==1){tab=1};TabButton("Ler depois (${savedItems.size})",tab==2){tab=2}};if(tab==0){Spacer(Modifier.height(10.dp));CategoryFilter(selectedCategory){selectedCategory=it}}}
   }
   val display=when(tab){1->readItems;2->savedItems;else->visible};val heading=when(tab){1->"Notícias lidas";2->"Ler depois";else->selectedCategory?.label?:"Principais e recentes"}
   when{tab==0&&loading&&items.isEmpty()->LoadingView();tab==0&&error!=null&&items.isEmpty()->ErrorView(error!!){refresh()};display.isEmpty()->Text(if(tab==1)"Você ainda não leu nenhuma notícia." else if(tab==2)"Nenhuma notícia salva para ler depois." else "Nenhuma notícia encontrada.",Modifier.padding(20.dp));else->{error?.takeIf{tab==0}?.let{Text(it,color=MaterialTheme.colorScheme.error,Modifier.padding(horizontal=20.dp))};if(opening)Row(Modifier.padding(horizontal=20.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){CircularProgressIndicator();Text("Abrindo notícia...")};LazyColumn(state=listState,modifier=Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(10.dp),contentPadding=PaddingValues(20.dp)){item{Text(heading,style=MaterialTheme.typography.headlineSmall)};items(display,key={it.id}){x->NewsCard(x,sources,x.url in savedUrls){openItem(x)}}}}}
 }}
}

@Composable private fun LoadingView(){Column(Modifier.padding(20.dp)){CircularProgressIndicator();Spacer(Modifier.height(12.dp));Text("Atualizando todas as fontes...")}}
@Composable private fun ErrorView(e:String,onRetry:()->Unit){Column(Modifier.padding(20.dp)){Text(e,color=MaterialTheme.colorScheme.error);Spacer(Modifier.height(12.dp));Button(onClick=onRetry){Text("Tentar novamente")}}}
@Composable private fun TabButton(label:String,selected:Boolean,onClick:()->Unit){if(selected)Button(onClick){Text(label)}else OutlinedButton(onClick){Text(label)}}
@Composable private fun CategoryFilter(selected:NewsCategory?,onSelected:(NewsCategory?)->Unit){Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){TabButton("Todos",selected==null){onSelected(null)};NewsCategory.entries.forEach{c->TabButton(c.label,selected==c){onSelected(c)}}}}

@Composable private fun SourceManager(sources:List<FeedSource>,urlInput:String,onUrlChange:(String)->Unit,sourceError:String?,onAdd:()->Unit,onBack:()->Unit,onToggle:(FeedSource)->Unit,onCategoryChange:(FeedSource,NewsCategory)->Unit,onDelete:(FeedSource)->Unit){Column(Modifier.fillMaxSize().padding(horizontal=20.dp)){Spacer(Modifier.height(14.dp));Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){Text("Gerenciar fontes",style=MaterialTheme.typography.headlineMedium);OutlinedButton(onBack){Text("Voltar")}};Text("Ative, desative ou altere a categoria de cada fonte.",style=MaterialTheme.typography.bodyMedium);Spacer(Modifier.height(14.dp));Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedTextField(urlInput,onUrlChange,Modifier.weight(1f),singleLine=true,label={Text("Adicionar site")});Button(onAdd){Text("Adicionar")}};sourceError?.let{Text(it,color=MaterialTheme.colorScheme.error)};Spacer(Modifier.height(14.dp));LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)){items(sources,key={it.id}){s->Card(Modifier.fillMaxWidth()){Row(Modifier.fillMaxWidth().padding(14.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(s.name,style=MaterialTheme.typography.titleMedium);Text(s.siteUrl,style=MaterialTheme.typography.bodySmall);TextButton({val n=NewsCategory.entries[(NewsCategory.entries.indexOf(s.category)+1)%NewsCategory.entries.size];onCategoryChange(s,n)}){Text(s.category.label)}};Switch(s.enabled,{onToggle(s)});if(s.id.startsWith("custom-"))TextButton({onDelete(s)}){Text("Excluir")}}}}}}}

@Composable private fun NewsCard(item:FeedItem,sources:List<FeedSource>,saved:Boolean,onClick:()->Unit){val source=sources.firstOrNull{it.id==item.sourceId};Card(Modifier.fillMaxWidth().clickable(onClick=onClick)){Column{item.imageUrl?.let{AsyncImage(it,item.title,Modifier.fillMaxWidth().height(190.dp),contentScale=ContentScale.Crop)};Column(Modifier.padding(14.dp)){Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Text(source?.name?:"Fonte",style=MaterialTheme.typography.labelMedium);item.publishedAt?.let{Text(publishedLabel(it),style=MaterialTheme.typography.labelMedium)}};Spacer(Modifier.height(8.dp));Text(item.title,style=MaterialTheme.typography.titleLarge);item.summary?.takeIf{it.isNotBlank()}?.let{Spacer(Modifier.height(6.dp));Text(it,maxLines=3,style=MaterialTheme.typography.bodyMedium)};if(saved)Text("🔖 Salva para ler depois",style=MaterialTheme.typography.labelMedium)}}}}

@Composable private fun ReaderContent(article:Article,item:FeedItem,saved:Boolean,onBack:()->Unit,onToggleSaved:()->Unit){Scaffold(topBar={TopAppBar(title={Text("Notícia")},navigationIcon={TextButton(onBack){Text("Voltar")}},actions={IconButton(onToggleSaved){Text(if(saved)"🔖" else "🔖")}})}){padding->LazyColumn(Modifier.fillMaxSize().padding(padding),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){item{Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End){TextButton(onToggleSaved){Text(if(saved)"Remover de Ler depois" else "🔖 Ler depois")}}};item{Text(article.title,style=MaterialTheme.typography.headlineMedium)};article.subtitle?.let{item{Text(it,style=MaterialTheme.typography.titleMedium)}};article.author?.let{item{Text("Por $it",style=MaterialTheme.typography.labelLarge)}};article.publishedAt?.let{item{Text(publishedLabel(it),style=MaterialTheme.typography.labelMedium)}};article.blocks.forEach{block->item{when(block){is ArticleBlock.Paragraph->Text(block.text,style=MaterialTheme.typography.bodyLarge);is ArticleBlock.Heading->Text(block.text,style=if(block.level<=2)MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge);is ArticleBlock.Image->Column{AsyncImage(block.url,block.altText?:block.caption,Modifier.fillMaxWidth().heightIn(max=320.dp),contentScale=ContentScale.FillWidth);block.caption?.let{Text(it,style=MaterialTheme.typography.bodySmall)}};is ArticleBlock.Quote->Text("“${block.text}”${block.author?.let{" — $it"}?:""}",style=MaterialTheme.typography.bodyLarge);is ArticleBlock.ListBlock->Column{block.items.forEachIndexed{i,t->Text(if(block.ordered)"${i+1}. $t" else "• $t",style=MaterialTheme.typography.bodyLarge)}}}}}}}}

private fun publishedLabel(v:Instant):String=v.atZone(ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm",Locale.getDefault()))
